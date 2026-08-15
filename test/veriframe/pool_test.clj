;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.pool-test
  "The warm-session pool, tested without Lean.

  Every case here is about the bookkeeping, which is where the bugs would be:
  two branches must not get the same session, a slot still importing must not be
  dropped on the floor, and a slot that failed to warm must fall through to the
  caller's own session rather than surfacing as an error. None of that needs a
  Mathlib import, so these run offline in milliseconds."
  (:require [clojure.test :refer [deftest is testing]]
            [veriframe.agent.state :as state]
            [veriframe.agent.tools :as tools]
            [veriframe.engine.lean-pool :as pool]
            [veriframe.engine.lean-repl :as lean-repl]
            [veriframe.llm.client :as llm]))

;; A stand-in for a session. The pool only ever asks lean-repl/alive? about it,
;; so a map with the same shape is enough.
(defn- fake-session [id] {:id id :alive (atom true)})

(defn- warm
  "A slot whose import has already finished."
  [v]
  (doto (promise) (deliver v)))

(defn- seed! [entries]
  (pool/shutdown!)
  (#'pool/reset-pool! entries))

(deftest checkout-returns-a-warm-session
  (seed! [(warm {:ok true :session (fake-session :a)})])
  (is (= :a (:id (pool/checkout! 0))))
  (is (nil? (pool/checkout! 0)) "the pool is empty once its only slot is taken"))

(deftest a-session-is-handed-out-once
  ;; Two branches sharing one Lean session would be silent corruption: the
  ;; session carries an environment, so one branch's declarations would be
  ;; visible to the other and a `sorry` could leak across an isolation boundary.
  (seed! (mapv #(warm {:ok true :session (fake-session %)}) [:a :b :c]))
  (let [taken (repeatedly 5 #(pool/checkout! 0))]
    (is (= [:a :b :c] (keep :id taken)))
    (is (= 3 (count (distinct (keep :id taken)))))))

(deftest concurrent-checkout-never-double-issues
  (seed! (mapv #(warm {:ok true :session (fake-session %)}) (range 20)))
  (let [got (->> (repeatedly 30 (fn [] (future (pool/checkout! 0))))
                 doall
                 (map deref)
                 (keep :id))]
    (is (= 20 (count got)))
    (is (= 20 (count (distinct got))) "no session was issued to two callers")))

(deftest a-failed-warm-falls-through
  ;; nil means "build your own", which the tool path already handles. Returning
  ;; an error instead would turn a slow path into a broken one.
  (seed! [(warm {:ok false :error "no toolchain"})])
  (is (nil? (pool/checkout! 0))))

(deftest a-still-importing-slot-is-put-back
  ;; The failure this guards: claim a pending slot, fail to wait, drop it. The
  ;; import keeps running, the session is never disposed, and it leaks for the
  ;; life of the process.
  (let [p (promise)]
    (seed! [p])
    (is (nil? (pool/checkout! 0)) "does not block past the wait budget")
    (is (= 1 (pool/pending-count)) "the slot is still in the pool")
    (deliver p {:ok true :session (fake-session :late)})
    (is (= :late (:id (pool/checkout! 0))) "and is usable once the import lands")))

(deftest counts_report_warm_and_pending_separately
  (let [p (promise)]
    (seed! [(warm {:ok true :session (fake-session :a)}) p])
    (testing "health must distinguish installed from ready"
      (is (= 1 (pool/warmed-count)))
      (is (= 1 (pool/pending-count))))
    (deliver p {:ok true :session (fake-session :b)})
    (is (= 2 (pool/warmed-count)))
    (is (= 0 (pool/pending-count)))))

(deftest shutdown-disposes-and-empties
  (seed! (mapv #(warm {:ok true :session (fake-session %)}) [:a :b]))
  (is (= 2 (pool/shutdown!)))
  (is (zero? (pool/warmed-count)))
  (is (nil? (pool/checkout! 0))))

;; --- session teardown -------------------------------------------------------

(deftest disposing-a-session-kills-the-repl-lake-spawned
  ;; vf-cfp. A session is `lake env .../repl`, so the repl the harness talks to
  ;; is a GRANDCHILD: jolt -> lake -> repl. jolt's destroy-tree cannot reach a
  ;; grandchild (jolt-hpdu), and BOTH kill paths went through it — dispose!
  ;; explicitly, and the JVM shutdown hook via :shutdown. So nothing ever killed
  ;; the repl. It was orphaned to init and stayed.
  ;;
  ;; Measured on this machine before the fix: 19 repl processes, five of them
  ;; reparented to pid 1, the oldest up 1 day 7 hours, 2.1GB resident. Each had
  ;; ~9s of CPU time — the Mathlib import and nothing since.
  ;;
  ;; The fix does not wait on jolt: collect the descendants BEFORE killing the
  ;; parent, then kill them by pid.
  (testing "descendants are collected before the parent dies, then killed"
    (let [killed (atom [])]
      (with-redefs [lean-repl/child-pids (fn [pid] (get {100 [200] 200 [300]} pid []))
                    lean-repl/kill-pid! (fn [pid] (swap! killed conj pid))]
        (let [order (atom [])
              s {:alive (atom true)
                 :out-stream (proxy [java.io.OutputStream] []
                               (write [_])
                               (close [] (swap! order conj :stream)))
                 :proc {:proc (reify Object)}}]
          (with-redefs [lean-repl/session-pid (fn [_] 100)]
            (lean-repl/dispose! s))
          (is (= [300 200] @killed)
              "the whole chain below the session is killed, deepest first, so no
               descendant is even briefly orphaned")
          (is (false? @(:alive s)) "and the session is marked dead")))))

  (testing "a session with no discoverable pid still disposes cleanly"
    (with-redefs [lean-repl/session-pid (fn [_] nil)
                  lean-repl/child-pids (fn [_] (throw (ex-info "must not be called" {})))
                  lean-repl/kill-pid! (fn [_] nil)]
      (is (nil? (lean-repl/dispose! {:alive (atom true) :proc nil})))))

  (testing "a failure killing one descendant does not strand the rest"
    (let [killed (atom [])]
      (with-redefs [lean-repl/session-pid (fn [_] 1)
                    lean-repl/child-pids (fn [pid] (get {1 [2 3 4]} pid []))
                    lean-repl/kill-pid! (fn [pid]
                                          (when (= pid 3) (throw (ex-info "denied" {})))
                                          (swap! killed conj pid))]
        (lean-repl/dispose! {:alive (atom true) :proc nil})
        (is (= [4 2] @killed))))))

;; --- session ownership ------------------------------------------------------

(deftest a-session-is-registered-where-teardown-can-always-find-it
  ;; vf-cfp, actual root cause. A Lean session lived ONLY in the branch map,
  ;; attached by lean-session! returning [session updated-branch]. Every path
  ;; that falls back to a pre-session branch value therefore drops the handle
  ;; permanently, and there are three:
  ;;
  ;;   1. the tool-level catch — `(catch Throwable e (unavailable branch …))`
  ;;      sits OUTSIDE the let that shadows `branch`, so it returns the branch
  ;;      without :lean. Five call sites, two Lean and three Octave.
  ;;   2. the turn deadline — a turn past 900000ms is abandoned and the beam
  ;;      keeps `b`, the branch as it was BEFORE the turn.
  ;;   3. a turn that throws — `(assoc b :status :abandoned …)`, same `b`.
  ;;
  ;; Path 2 is the likely dominant one: a Mathlib import is ~378000ms and
  ;; "scales with what else is running", so a provider call plus an import plus
  ;; a command can pass the deadline while several branches first touch Lean at
  ;; once. Every leaked repl found on this machine had about nine seconds of
  ;; CPU — the import, and nothing after.
  ;;
  ;; beam.clj already states the principle three lines above the leak, for
  ;; Prolog: "the stop path must not depend on the agent's state — the RAX
  ;; manager could always halt the Lisp task no matter what it believed."
  ;; Prolog sessions go in a run-scoped atom swept unconditionally. Lean and
  ;; Octave now do too, so a lost branch value costs a turn and not a process.
  (testing "the session is registered even when the tool then throws"
    (let [registry (atom [])
          disposed (atom 0)]
      (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                    lean-repl/mathlib-env (fn [& _] nil)
                    pool/checkout! (fn [& _] nil)
                    lean-repl/run-command (fn [& _] (throw (ex-info "REPL died" {})))
                    lean-repl/dispose! (fn [_] (swap! disposed inc))]
        (let [r (tools/run-tool {:branch (state/new-branch {:id "B1" :problem "p"})
                                 :turn 1
                                 :engine-sessions registry
                                 :tool-name "verify_lean"
                                 :args {:claim "the bound holds for every edge"
                                        :lean "theorem t : True := trivial"}})]
          (is (nil? (:lean (:branch r)))
              "the branch really has lost the handle — that is the bug's shape")
          (is (= 1 (count @registry))
              "but the registry kept it, so teardown can still reach it")
          (is (= :lean (:kind (first @registry))))))))

  (testing "a session created on a turn the beam later discards is still reachable"
    ;; The deadline path in miniature: the branch value is thrown away entirely.
    (let [registry (atom [])]
      (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                    lean-repl/mathlib-env (fn [& _] nil)
                    pool/checkout! (fn [& _] nil)
                    lean-repl/run-command (fn [& _] {:ok true :sorries []})
                    llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
        (tools/run-tool {:branch (state/new-branch {:id "B1" :problem "p"})
                         :turn 1
                         :engine-sessions registry
                         :tool-name "verify_lean"
                         :args {:claim "the bound holds for every edge"
                                :lean "theorem t : True := trivial"}})
        (is (= 1 (count @registry))
            "registration happens at creation, not at a successful return"))))

  (testing "no registry means the tools behave exactly as before"
    (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                  lean-repl/mathlib-env (fn [& _] nil)
                  pool/checkout! (fn [& _] nil)
                  lean-repl/run-command (fn [& _] {:ok true :sorries []})
                  llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
      (is (= :success (:category (tools/run-tool
                                  {:branch (state/new-branch {:id "B1" :problem "p"})
                                   :turn 1 :tool-name "verify_lean"
                                   :args {:claim "the bound holds for every edge"
                                          :lean "theorem t : True := trivial"}})))))))

;; --- recovering from a dead session -----------------------------------------

(deftest a-dead-session-is-replaced-not-handed-back
  ;; A branch whose Lean session died was handed the corpse on every later
  ;; call, because lean-session! checked only whether :lean was PRESENT, never
  ;; whether it was alive. Every subsequent Lean call then failed with "the
  ;; session is dead" — and those were charged :failure, so the branch was
  ;; culled for an outage it could not recover from.
  ;;
  ;; gen-26 B3: t28 a genuine Lean error, t30 "REPL I/O failed", t32 "session
  ;; is dead", culled after three consecutive failures. It never called Lean
  ;; again, because every call would have used the same dead session.
  (testing "a live session is reused"
    (let [created (atom 0)]
      (with-redefs [lean-repl/create-session (fn [& _] (swap! created inc) {:id "new"})
                    lean-repl/mathlib-env (fn [& _] nil)
                    lean-repl/alive? (fn [_] true)
                    pool/checkout! (fn [& _] nil)
                    lean-repl/run-command (fn [& _] {:ok true :sorries []})
                    llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
        (tools/run-tool {:branch (assoc (state/new-branch {:id "B1" :problem "p"})
                                        :lean {:id "existing"})
                         :turn 1 :tool-name "verify_lean"
                         :args {:claim "the bound holds on every edge"
                                :lean "theorem t : True := trivial"}})
        (is (= 0 @created) "a healthy session must not be thrown away"))))

  (testing "a dead session is discarded and a fresh one obtained"
    (let [created (atom 0)]
      (with-redefs [lean-repl/create-session (fn [& _] (swap! created inc) {:id "new"})
                    lean-repl/mathlib-env (fn [& _] nil)
                    lean-repl/alive? (fn [s] (not= "corpse" (:id s)))
                    pool/checkout! (fn [& _] nil)
                    lean-repl/run-command (fn [& _] {:ok true :sorries []})
                    llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
        (let [r (tools/run-tool {:branch (assoc (state/new-branch {:id "B1" :problem "p"})
                                                :lean {:id "corpse"})
                                 :turn 1 :tool-name "verify_lean"
                                 :args {:claim "the bound holds on every edge"
                                        :lean "theorem t : True := trivial"}})]
          (is (= 1 @created) "the branch gets a working session rather than the corpse")
          (is (= :success (:category r)))
          (is (= "new" (:id (:lean (:branch r)))))))))

  (testing "an engine outage is not charged to the branch"
    ;; unavailable already says why, and says it about this exact incident two
    ;; generations earlier: "gen-18 B3 was culled after six consecutive
    ;; failures... One of the six was `Lean is unavailable`, a fact about the
    ;; process pool." That reasoning was applied to the thrown-exception path
    ;; and not to the returned-error path, which is the common one.
    (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                  lean-repl/mathlib-env (fn [& _] nil)
                  lean-repl/alive? (fn [_] true)
                  pool/checkout! (fn [& _] nil)
                  lean-repl/run-command (fn [& _] {:error "Lean REPL I/O failed"})]
      (let [r (tools/run-tool {:branch (state/new-branch {:id "B1" :problem "p"})
                               :turn 1 :tool-name "verify_lean"
                               :args {:claim "the bound holds on every edge"
                                      :lean "theorem t : True := trivial"}})]
        (is (= :neutral (:category r))
            "a dead REPL is a fact about the process pool, not about the claim")
        (is (nil? (:failure r)) "and it does not enter the failure log")))))

