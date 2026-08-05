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
   (run-op session {:op "check" :expr (str expr) :tol (or tol 0)})))

(defn snapshot [session] @(:log session))
