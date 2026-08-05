;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU General Public License as
;; published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public
;; License along with this program. If not, see
;; <https://www.gnu.org/licenses/>.

(ns veriframe.engine.lean-pool
  "Lean sessions with Mathlib already imported, warmed at startup.

  `import Mathlib` costs 377927ms on an idle machine with a warm olean cache.
  Paid inside a branch turn that is most of the turn deadline gone before the
  model has done anything, and every branch pays it again because each one gets
  its own session. Paid at boot it is startup cost, which is the right place for
  it: the harness is a long-lived process and the whole design already assumes
  you leave it running.

  Warming does not block startup. Each slot is a promise handed to a background
  thread, and a branch that asks for a session before the import finishes waits
  on the promise rather than starting a second import beside it — which is the
  failure this replaces, not an improvement on it.

  A branch that finds the pool empty falls back to building its own session, so
  this is an optimisation and never a dependency. Nothing here is required for
  the engine to work."
  (:require [clojure.tools.logging :as log]
            [veriframe.engine.lean-repl :as lean-repl]))

;; Slots are promises delivered with {:ok true :session s} or {:ok false :error}.
;; A vector rather than a queue: order does not matter, and a vector makes the
;; swap! that claims a slot a single compare-and-set.
(defonce ^:private pool (atom []))

(defn- reset-pool!
  "Replace the slot vector wholesale. A test seam: the bookkeeping is worth
  testing and a real Mathlib import takes six minutes, so the tests seed
  already-delivered promises instead."
  [slots]
  (reset! pool (vec slots)))

(defn warmed-count
  "Slots whose import has finished. For /health and the smoke probes, which
  should not report Lean as ready on the strength of a file existing."
  []
  (count (filter #(realized? %) @pool)))

(defn pending-count []
  (count (remove #(realized? %) @pool)))

(defn warm!
  "Start `n` sessions importing Mathlib in the background. Returns immediately.

  Idempotent in the sense that calling it twice adds slots rather than
  replacing them; `shutdown!` is what empties the pool."
  [lean-cfg n]
  (when (and (pos? (or n 0)) (lean-repl/available? lean-cfg))
    (log/info "warming" n "Lean session(s); Mathlib import runs in the background")
    (let [slots (vec (repeatedly n promise))]
      (swap! pool into slots)
      (doseq [[i p] (map-indexed vector slots)]
        (future
          (let [t0 (System/currentTimeMillis)]
            (try
              (let [s (lean-repl/create-session lean-cfg)]
                (lean-repl/mathlib-env s)
                (log/info "Lean session" i "warm after"
                          (- (System/currentTimeMillis) t0) "ms")
                (deliver p {:ok true :session s}))
              (catch Throwable e
                (log/warn "Lean session" i "failed to warm after"
                          (- (System/currentTimeMillis) t0) "ms:" (ex-message e))
                (deliver p {:ok false :error (ex-message e)}))))))
      n)))

(defn- claim-slot!
  "Remove and return one slot, or nil if the pool is empty. Atomic against other
  branches claiming concurrently."
  []
  (let [[old _] (swap-vals! pool #(if (seq %) (subvec % 1) %))]
    (first old)))

(defn checkout!
  "A warmed session, or nil if none is available.

  Blocks up to `wait-ms` on a slot whose import is still running, because
  waiting for an import already in flight beats starting a second one next to
  it. A slot that failed to warm returns nil and the caller builds its own,
  which is also what happens when the pool is empty.

  A checked-out session is NOT returned to the pool. It belongs to the branch
  for the rest of the run, which is what keeps the existing per-branch isolation
  intact — one branch's `sorry`-laden environment must not reach another."
  ([] (checkout! 0))
  ([wait-ms]
   (when-let [p (claim-slot!)]
     (let [r (deref p wait-ms ::waiting)]
       (cond
         (= ::waiting r)
         ;; Put it back: the import is still running and will finish for
         ;; whoever asks next. Dropping it here would leak the session.
         (do (swap! pool conj p) nil)

         (:ok r)
         (let [s (:session r)]
           (if (lean-repl/alive? s)
             s
             ;; Warmed, then died before anyone claimed it.
             (recur wait-ms)))

         :else nil)))))

(defn shutdown!
  "Dispose every warmed session. Best effort — one failure must not strand the
  rest. Slots still importing are abandoned; their session is killed when the
  process exits, since create-session spawns with :shutdown destroy-tree."
  []
  (let [[old _] (reset-vals! pool [])]
    (doseq [p old]
      (when (realized? p)
        (try (some-> (:session @p) lean-repl/dispose!)
             (catch Throwable e (log/warn "disposing warm Lean session:" (ex-message e))))))
    (count old)))
