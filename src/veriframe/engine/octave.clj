;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.engine.octave
  "GNU Octave as a numerical engine, one invocation per call.

  ## What this engine can and cannot establish

  The other three engines DECIDE. Prolog exhausts a finite domain, Z3 decides a
  theory, Lean checks a proof. Octave COMPUTES, in floating point, which is a
  different kind of evidence: it says what happened for the inputs it was given
  at the precision it was given them. It cannot establish that a claim holds for
  all reals, and a check that came out true within a tolerance has proved
  nothing about exact arithmetic.

  That distinction is carried in the data rather than left to the prompt.
  `check` records the tolerance a claim was made at and whether the verdict
  rests on exact arithmetic, and the tool layer refuses to treat an approximate
  result as though it were a decision.

  ## Why an invocation per call, not a session

  The other two subprocess engines hold a persistent read-eval-print session.
  That does not work here. Octave's `fgetl(stdin)` block-buffers on a pipe: it
  returns nothing until roughly 4KB has arrived or stdin closes, so a
  request-per-line protocol deadlocks on the first request. Confirmed by
  sending 8KB of pings to a session that had answered nothing, at which point
  every reply arrived at once.

  So each call is a fresh `octave` process that loads the branch's workspace,
  runs one command, saves it back, and prints one JSON object. About 0.8s
  against a branch turn averaging 37 seconds.

  The shape turns out to be better than the session it replaces, not merely
  adequate. There is no session to wedge, so none of the busy-flag and
  kill-on-abandon machinery veriframe.engine.prolog needs exists here, and each
  call goes through engine/proc.clj, so it is bounded and killable exactly like
  z3.

  ## Subprocess, not in-process

  Octave's API is C++ and jolt.ffi binds C, so embedding would need an
  extern \"C\" shim built against Octave's headers on every build machine. It
  would also make the combined work GPLv3, which this project cannot be: jolt
  is EPL-2.0 without the secondary-licence option and three more dependencies
  are EPL-1.0 by inheritance from Clojure code. Across a pipe none of that
  applies."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [veriframe.engine.proc :as proc]))

(def default-timeout-ms 120000)

(defn available?
  ([] (available? "octave"))
  ([bin] (proc/available? (or bin "octave"))))

(defn create-session
  "A workspace directory for one branch. No process is started: a session here
  is a place on disk plus a replay log, and each call spawns its own octave."
  ([] (create-session nil))
  ([{:keys [bin timeout-ms] :or {bin "octave"}}]
   (let [dir (java.io.File/createTempFile "veriframe-octave" "")]
     (.delete dir)
     (.mkdirs dir)
     (.deleteOnExit dir)
     ;; vf_run.m is copied out because in an AOT image the resource is embedded
     ;; and there is no file to put on octave's load path. The NAME matters:
     ;; Octave resolves a function by file name, so it cannot be a randomly
     ;; named temp file the way the Prolog bootstrap can.
     (let [m (io/file dir "vf_run.m")]
       (spit m (slurp (io/resource "octave/vf_run.m")))
       (.deleteOnExit m))
     {:dir (.getAbsolutePath dir)
      :bin bin
      :timeout-ms (or timeout-ms default-timeout-ms)
      :log (atom [])
      :alive (atom true)})))

(defn alive? [session] (boolean (some-> session :alive deref)))

(defn dispose!
  "Drop the workspace. Nothing is running, so this only removes files."
  [session]
  (when session
    (reset! (:alive session) false)
    (try
      (doseq [f (reverse (file-seq (io/file (:dir session))))]
        (.delete f))
      (catch Throwable _ nil))
    nil))

(defn- run-op
  "Write the request, run one octave, read one JSON object back."
  [session req]
  (if-not (alive? session)
    {:ok false :error "Octave session has been disposed."}
    (let [{:keys [dir bin timeout-ms]} session]
      (try
        (spit (io/file dir "request.json") (json/write-str req))
        (let [{:keys [out err timeout]}
              (proc/run {:timeout-ms timeout-ms}
                        bin "--no-gui" "--quiet" "--no-init-file"
                        "-p" dir "--eval" (str "vf_run('" dir "')"))]
          (cond
            timeout
            {:ok false
             :error (str "Octave did not finish within " timeout-ms
                         "ms and was killed. Octave has no per-statement time"
                         " limit, so an infinite loop or a very large solve"
                         " reaches this rather than failing inside the script.")}

            :else
            ;; The reply is the last line that looks like a JSON object.
            ;; Octave writes a trailing blank line, and a warning the script
            ;; did not suppress would land on stdout ahead of the reply.
            (if-let [line (->> (str/split-lines (str out))
                               (filter #(str/starts-with? (str/trim %) "{"))
                               last)]
              (try (json/read-str line :key-fn keyword)
                   (catch Throwable _
                     {:ok false :error (str "Octave returned unparseable JSON: "
                                            (subs line 0 (min 300 (count line))))}))
              {:ok false
               :error (str "Octave produced no reply."
                           (when-not (str/blank? (str err))
                             (str " stderr: " (subs (str err) 0 (min 400 (count (str err)))))))})))
        (catch Throwable e
          {:ok false :error (str "Octave call failed: " (ex-message e))})))))

(defn ping [session] (run-op session {:op "ping"}))

(defn eval-code!
  "Run `code` against the branch's workspace, keeping whatever it defines.
  Returns {:ok true :output s} or {:ok false :error s}."
  [session code]
  (let [r (run-op session {:op "eval" :code (str code)})]
    (when (:ok r) (swap! (:log session) conj {:code (str code)}))
    r))

(defn explain-check-error
  "Turn Octave's own complaint about a bad `expr` into one that says what
  `expr` is for, or pass it through.

  `check` evaluates ONE expression against the workspace. A model that writes
  statements into it, or tries to define a helper there, gets a parser message
  with no hint of that: `invalid use of statement list`, or the name it just
  tried to define reported as undefined. Neither points at `octave_eval`,
  which is where statements belong and the only reason the workspace has
  anything in it.

  Measured cost: three of five consecutive failed turns on one gen-14 branch,
  which was building an LP check and kept losing the turn to this. vf_check
  already explains itself when handed a matrix — `wrap it in all(...) or
  any(...) to say which you mean` — and these two cases had been left raw.

  `shape` says what the caller's `expr` has to reduce to, because `check` and
  `measure` want different things out of it. Both messages point at
  octave_eval, which is the actual fix either way."
  ([error] (explain-check-error error "reduces to a scalar logical"))
  ([error shape]
   (let [e (str error)]
     (cond
       (or (str/includes? e "invalid use of statement list")
           (re-find #"(?i)parse error.*\n.*=\s*$" e))
       (str "`expr` must be ONE expression that " shape ", and"
            " this is a list of statements. Run the statements with octave_eval"
            " first — the workspace persists across calls, so anything they"
            " define is still there — then pass just the expression that"
            " settles the claim. Octave said: " e)

       (re-find #"'([^']+)' undefined" e)
       (let [n (second (re-find #"'([^']+)' undefined" e))]
         (str "`" n "` does not exist in the workspace. This tool only"
              " EVALUATES an expression; it cannot define anything. If `" n "`"
              " is a helper function or a value you meant to compute, create it"
              " with octave_eval first and then use it here. Octave said: " e))

       ;; The vector case, which the all(...)/any(...) advice sends the wrong
       ;; way when what the branch holds is a SWEEP. Told to collapse a column
       ;; of counts to one boolean, a model writes a comparison against a
       ;; closed form — and run 0d0c3560's B2 wrote one that was true by
       ;; construction, comparing `size(states,1).^m` to `2.^m` without ever
       ;; reading the counts it had measured. `measure` takes the column.
       (str/includes? e "not a scalar")
       (str e "\n\nIf that vector IS the result — a sweep, a column of counts,"
            " a rate per parameter — then it is a measurement and not a"
            " verdict, and `measure` banks it as itself. Collapsing it to a"
            " boolean here is how a check ends up comparing a formula to"
            " itself instead of to what was computed.")

       :else e))))

(defn check
  "Evaluate `expr`, which must reduce to a logical scalar.

  `tol` is the tolerance the claim was made at; 0 means exact. It is recorded
  rather than used, because what matters downstream is that a number was
  STATED: `true within 1e-9` is a different claim from `true`, and the harness
  should not have to guess which it was handed.

  A false verdict is {:ok true :verdict false} — the claim was evaluated and
  found false, which is a result. {:ok false} means it could not be evaluated."
  ([session expr] (check session expr 0))
  ([session expr tol]
   (let [r (run-op session {:op "check" :expr (str expr) :tol (or tol 0)})]
     (cond-> r
       (and (not (:ok r)) (:error r))
       (update :error explain-check-error)))))

(defn measure
  "Evaluate `expr` for its VALUE: {:ok true :value v :text s}.

  `check` is the decision path and takes a scalar logical, which for a long
  time was the only way an Octave turn banked anything. That left the engine's
  most characteristic output — a sweep, a rate, the point at which something
  breaks — with nowhere to go, because none of it is a boolean. A branch doing
  exactly the work the problem called for scored nothing for it (vf-0of).

  What comes back is the number Octave computed, not a claim about it. The tool
  layer writes that number into the artifact, so a measurement cannot cite a
  value the run never produced."
  [session expr]
  (let [r (run-op session {:op "value" :expr (str expr)})]
    (cond-> r
      (and (not (:ok r)) (:error r))
      (update :error explain-check-error
              "produces a number or a short vector"))))

(defn snapshot [session] @(:log session))
