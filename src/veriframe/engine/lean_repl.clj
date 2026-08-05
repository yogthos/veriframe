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

(ns veriframe.engine.lean-repl
  "A long-lived leanprover-community/repl subprocess.

  The whole reason this is a persistent process is cost: `import Mathlib` takes
  tens of seconds, and a harness that paid that per tactic could not develop a
  proof step by step. Imported once, each subsequent step is sub-second.

  Protocol, per the repl README: JSON in, JSON out, BLANK-LINE separated rather
  than line separated. A reply is pretty-printed across several lines, so the
  reader accumulates until it sees an empty line. That is the difference from
  the Prolog session, which frames on newlines.

  Two invocation details are load-bearing and cost real time to find. The
  binary must be spawned through `lake env` from the workspace directory, or it
  cannot find `lean` at all and dies with exit 255. And the repl has to be
  pinned to the commit whose toolchain matches the workspace: forcing a
  toolchain onto HEAD builds against options that Lean version does not have."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [clojure.tools.logging :as log]))

;; import Mathlib is the slow case; a tactic step after it is sub-second.
;; Per-command: elaborating a declaration or applying a tactic. Seconds in the
;; normal case; generous here because a Mathlib-heavy `simp` or `decide` can run
;; long, and killing a tactic that was about to succeed costs the branch a turn.
(def default-timeout-ms 300000)

;; The one-time `import Mathlib`, which is a different animal — 377927ms
;; measured on a warm cache with an idle machine. The ceiling is well above that
;; because the cost scales with what else is running: a beam warms one session
;; per branch, and they contend.
(def default-import-timeout-ms 1200000)

(defn available?
  "Whether the toolchain is present. Phase 5 is the only thing that needs it,
  so everything else has to keep working when it is not."
  [{:keys [workspace repl-bin]}]
  (and (.exists (io/file (or repl-bin "")))
       (.exists (io/file (or workspace "")))))

(defn create-session
  "Spawn the repl. Returns a session, or throws with an actionable message when
  the toolchain is missing."
  [{:keys [workspace repl-bin timeout-ms] :as cfg}]
  (when-not (available? cfg)
    (throw (ex-info (str "The Lean toolchain is not set up. Run tools/setup-lean.sh"
                         " (fetches Mathlib and builds the repl). Looked for "
                         repl-bin " and " workspace)
                    {:workspace workspace :repl-bin repl-bin})))
  (let [abs-repl (.getAbsolutePath (io/file repl-bin))
        ;; An absolute path to lake, not a bare name. :extra-env sets PATH for
        ;; the child, but the executable itself is resolved against the
        ;; PARENT's PATH, and elan installs outside it.
        lake (or (:lake-bin cfg)
                 (let [elan (io/file (System/getProperty "user.home") ".elan/bin/lake")]
                   (if (.exists elan) (.getAbsolutePath elan) "lake")))
        proc (p/process [lake "env" abs-repl]
                        {:dir workspace :shutdown p/destroy-tree})]
    {:proc proc
     :out-stream (:in proc)
     :reader (java.io.BufferedReader.
              (java.io.InputStreamReader. (:out proc) "UTF-8"))
     :timeout-ms (or timeout-ms default-timeout-ms)
     :import-timeout-ms (or (:import-timeout-ms cfg) default-import-timeout-ms)
     :busy (atom false)
     :alive (atom true)
     ;; The env id `import Mathlib` produced. Every later command builds on it.
     :mathlib-env (atom nil)}))

(defn alive? [s] (boolean (some-> s :alive deref)))

(defn dispose! [s]
  (when s
    (reset! (:alive s) false)
    (try (.close (:out-stream s)) (catch Throwable _ nil))
    (try (p/destroy-tree (:proc s)) (catch Throwable _ nil))
    nil))

(defn- read-reply
  "Accumulate lines until a blank one. Returns nil at end of stream."
  [^java.io.BufferedReader r]
  (loop [acc []]
    (let [line (.readLine r)]
      (cond
        (nil? line) (when (seq acc) (str/join "\n" acc))
        (str/blank? line) (if (seq acc) (str/join "\n" acc) (recur acc))
        :else (recur (conj acc line))))))

(defn send-command
  "One request, one reply.

  Guarded by a compare-and-set flag rather than a monitor, and a busy session
  is KILLED rather than waited on. That is not a style preference. The beam
  abandons a branch turn that blows its deadline, but the abandoned work keeps
  running and, under `locking`, keeps the session monitor. The next turn then
  blocked on that monitor forever with no timeout, so the deadline fired
  exactly once and the branch wedged permanently, taking the per-turn barrier
  and the whole run with it. A full benchmark sweep died this way.

  Killing is also the only honest option: the abandoned request will eventually
  write a reply nobody read, so every later reply on this session would be
  misframed by one. The session is unrecoverable, not merely busy.

  `timeout-override-ms` exists for one caller: importing Mathlib takes minutes
  while every other command takes seconds, and a single knob cannot serve both.
  Sized to the import, a wedged tactic goes undetected for minutes; sized to a
  tactic, the import cannot finish. See mathlib-env."
  ([session cmd] (send-command session cmd nil))
  ([session cmd timeout-override-ms]
  (cond
    (not (alive? session))
    {:ok false :error "The Lean REPL session is dead."}

    (not (compare-and-set! (:busy session) false true))
    (do (reset! (:alive session) false)
        {:ok false
         :error (str "This Lean session is still executing a request that was"
                     " abandoned, so its reply stream can no longer be framed."
                     " The session has been killed.")})

    :else
    (try
      (let [{:keys [out-stream reader]} session
            timeout-ms (or timeout-override-ms (:timeout-ms session))]
        (try
          (.write out-stream (.getBytes (str (json/write-str cmd) "\n\n") "UTF-8"))
          (.flush out-stream)
          (let [raw (deref (future (read-reply reader)) timeout-ms ::timeout)]
            (cond
              (= ::timeout raw)
              (do (reset! (:alive session) false)
                  {:ok false :error (str "The Lean REPL did not reply within "
                                         timeout-ms "ms and has been killed.")})
              (nil? raw)
              (do (reset! (:alive session) false)
                  {:ok false :error "The Lean REPL closed its output stream."})
              :else
              (try
                (assoc (json/read-str raw :key-fn keyword) :ok true)
                (catch Throwable _
                  {:ok false :error (str "The Lean REPL returned non-JSON: "
                                         (subs raw 0 (min 400 (count raw))))}))))
          (catch Throwable e
            (reset! (:alive session) false)
            {:ok false :error (str "Lean REPL I/O failed: " (ex-message e))})))
      (finally (reset! (:busy session) false))))))

(defn mathlib-env
  "The environment id with Mathlib imported, importing it on first use. Every
  command and proof in this session is based on it.

  Measured at 377927ms on a warm olean cache with nothing else running, which is
  why this gets its own timeout rather than the per-command one. Under the old
  shared 300000ms ceiling the import was killed just short of finishing and
  every Lean tool call failed — for as long as the tools were undocumented and
  unreachable, nothing ever ran this far to notice."
  [session]
  (or @(:mathlib-env session)
      (let [t0 (System/currentTimeMillis)
            _ (log/info "importing Mathlib into the Lean REPL (slow, once)")
            r (send-command session {:cmd "import Mathlib"} (:import-timeout-ms session))]
        (if (and (:ok r) (:env r))
          (do (log/info "Mathlib imported in" (- (System/currentTimeMillis) t0) "ms")
              (reset! (:mathlib-env session) (:env r)))
          (throw (ex-info (str "Could not import Mathlib: " (or (:error r) (pr-str r)))
                          {:reply r}))))))

(defn- errors
  "Only error-severity messages. Lean warns constantly and a warning is not a
  failed proof — treating them alike is how `sorry` gets recorded as verified."
  [messages]
  (filter #(= "error" (:severity %)) messages))

(defn run-command
  "Elaborate `code` against the session's Mathlib env, or against `env`.

  Returns {:ok bool :env n :messages [...] :sorries [...] :errors [...]}."
  ([session code] (run-command session code nil))
  ([session code env]
   (let [base (or env (mathlib-env session))
         r (send-command session {:cmd code :env base})]
     (if-not (:ok r)
       r
       (let [errs (errors (:messages r))]
         {:ok (empty? errs)
          :env (:env r)
          :messages (:messages r)
          :errors (vec errs)
          :sorries (vec (:sorries r))})))))

(defn apply-tactic
  "One tactic against a proof state. Returns the new state and its goals; an
  empty goal list means the proof is closed."
  [session tactic proof-state]
  (let [r (send-command session {:tactic tactic :proofState proof-state})]
    (if-not (:ok r)
      r
      (let [errs (errors (:messages r))]
        {:ok (empty? errs)
         :proof-state (:proofState r)
         :goals (vec (:goals r))
         :closed? (and (empty? errs) (empty? (:goals r)))
         :messages (:messages r)
         :errors (vec errs)}))))
