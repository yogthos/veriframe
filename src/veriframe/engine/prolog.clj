;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.engine.prolog
  "A persistent SWI-Prolog session per branch.

  The TypeScript harness ran SWI-Prolog compiled to WebAssembly, which has no
  library(time), so it approximated wall-clock limits with an inference
  counter and a marker atom. This talks to the real `swipl` binary over a
  line-framed JSON protocol (resources/prolog/session.pl), which gets
  call_with_time_limit/2 and library(http/json) for free.

  The bootstrap file is not an implementation detail. #> and #< are operators
  library(clpfd) defines at load, and a Prolog term is read in full before it
  runs, so a session that has not already loaded clpfd cannot parse a clpfd
  goal at all. Consulting the file at startup is what puts those operators in
  scope for everything that follows.

  A session records every assert in order. That log is the snapshot: restoring
  means replaying it into a fresh process, which is what the safe-state rung
  needs and what `retract` alone cannot give (an anonymous assert has no name
  to take back)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.process :as p]
            [veriframe.engine.lint :as lint]))

(def default-query-timeout-s 10)
(def default-limit 100)
;; Ceiling on waiting for a reply. The Prolog side bounds the goal itself, so
;; hitting this means the process is wedged rather than the goal being slow,
;; and the session is marked dead instead of retried.
(def default-read-timeout-ms 60000)

(defn- bootstrap-file
  "Materialize session.pl to a temp file. It is read through io/resource so
  the path works the same interpreted and inside an AOT binary, where the
  resource is embedded and there is no file on disk to hand swipl."
  []
  (let [text (slurp (io/resource "prolog/session.pl"))
        f (java.io.File/createTempFile "veriframe-session" ".pl")]
    (.deleteOnExit f)
    (spit f text)
    (.getAbsolutePath f)))

(defn create-session
  "Spawn a swipl process with the bootstrap consulted. Returns a session."
  ([] (create-session nil))
  ([{:keys [bin read-timeout-ms] :or {bin "swipl"}}]
   (let [pl (bootstrap-file)
         proc (p/process [bin "-q" "-g" "main" "-t" "halt" pl]
                         {:shutdown p/destroy-tree})]
     {:proc proc
      ;; clojure.java.io/writer and /reader do not accept a raw stream on
      ;; this host, so the OutputStream is written as bytes and the
      ;; InputStream is wrapped by hand.
      :out-stream (:in proc)
      :reader (java.io.BufferedReader.
               (java.io.InputStreamReader. (:out proc) "UTF-8"))
      :read-timeout-ms (or read-timeout-ms default-read-timeout-ms)
      :busy (atom false)
      ;; ordered [{:code :name}] — the snapshot, see the ns docstring
      :log (atom [])
      :alive (atom true)})))

(defn alive? [session] (boolean (some-> session :alive deref)))

(defn dispose!
  "Close stdin so the loop reaches end_of_file, then destroy the tree. Best
  effort throughout: a branch tearing down must not throw past the run."
  [session]
  (when session
    (reset! (:alive session) false)
    (try (.close (:out-stream session)) (catch Throwable _ nil))
    (try (p/destroy-tree (:proc session)) (catch Throwable _ nil))
    nil))

(defn- send-command
  "Write one JSON line, read one JSON line.

  Guarded by a compare-and-set flag rather than a monitor, and a busy session
  is killed rather than waited on. That is not a style preference: the beam
  abandons a branch turn that blows its deadline, but the abandoned work keeps
  running and, under `locking`, keeps the monitor. The next turn then blocked
  on that monitor forever with no timeout, so the deadline fired exactly once
  and the branch wedged permanently — taking the whole barrier with it. A full
  benchmark run died this way.

  Killing is also the only honest option. The abandoned request will eventually
  write a reply into the pipe that nobody read, so every subsequent reply on
  this session would be misframed by one. The session is unrecoverable the
  moment a request is abandoned."
  [session cmd]
  (cond
    (not (alive? session))
    {:ok false :error "Prolog session is dead."}

    (not (compare-and-set! (:busy session) false true))
    (do (reset! (:alive session) false)
        {:ok false
         :error (str "This Prolog session is still executing a previous request"
                     " that was abandoned, so its reply stream can no longer be"
                     " framed. The session has been killed.")})

    :else
    (try
      (let [{:keys [out-stream reader read-timeout-ms]} session]
        (try
          (.write out-stream (.getBytes (str (json/write-str cmd) "\n") "UTF-8"))
          (.flush out-stream)
          (let [line (deref (future (.readLine reader)) read-timeout-ms ::timeout)]
            (cond
              (= ::timeout line)
              (do (reset! (:alive session) false)
                  {:ok false
                   :error (str "The Prolog process did not reply within "
                               read-timeout-ms "ms and has been killed. The goal's own"
                               " time limit should have fired first, so this is a wedged"
                               " process rather than a slow goal.")})

              (nil? line)
              (do (reset! (:alive session) false)
                  {:ok false :error "The Prolog process closed its output stream."})

              :else
              (json/read-str line :key-fn keyword)))
          (catch Throwable e
            (reset! (:alive session) false)
            {:ok false :error (str "Prolog session I/O failed: " (ex-message e))})))
      (finally (reset! (:busy session) false)))))

(defn ping [session] (send-command session {:op "ping"}))

(defn assert-rules!
  "Load Prolog text into the session. A `name` makes the clauses retractable;
  without one they are permanent, which is the distinction the harness draws
  between a tentative rule and a committed one.

  Every successful assert is appended to the session log, so the log stays a
  faithful replay script. A failed assert is not recorded, because replaying
  it would fail the same way."
  ([session code] (assert-rules! session code nil))
  ([session code {:keys [name]}]
   (let [{:keys [ok warnings]} (lint/lint-prolog-program code)]
     (if-not ok
       {:ok false :error (str "Prolog lint rejected the program — nothing loaded:\n  • "
                              (str/join "\n  • " warnings))}
       (let [reply (send-command session (cond-> {:op "assert" :code code}
                                           name (assoc :name name)))]
         (when (:ok reply)
           (swap! (:log session) conj (cond-> {:code code} name (assoc :name name))))
         reply)))))

(defn retract-rule!
  "Erase the clauses a named assert added. Also drops it from the session log,
  so a later replay reproduces the session as it stands rather than as it was."
  [session name]
  (let [reply (send-command session {:op "retract" :name name})]
    (when (:ok reply)
      (swap! (:log session) (fn [entries] (vec (remove #(= name (:name %)) entries)))))
    reply))

(defn query
  "Run a goal. Returns {:ok true :answers [{:bindings {} :formatted s}]
  :truncated bool} or {:ok false :error s [:timeout true]}.

  An empty answer list is a FAILED goal, not an error: the claim is false.
  A thrown goal is :ok false: the encoding is broken. Collapsing the two is
  how a harness tells a model its correct encoding is buggy."
  ([session goal] (query session goal nil))
  ([session goal {:keys [limit timeout-s]}]
   (let [{:keys [ok warnings]} (lint/lint-prolog-query goal)]
     (if-not ok
       {:ok false :error (str "Prolog lint rejected the query — nothing run:\n  • "
                              (str/join "\n  • " warnings))}
       (send-command session {:op "query"
                              :goal (lint/normalize-query goal)
                              :limit (or limit default-limit)
                              :timeout (or timeout-s default-query-timeout-s)})))))

(defn snapshot
  "The ordered assert log. Hand this to `restore` to rebuild the session."
  [session]
  @(:log session))

(defn restore
  "Build a fresh session and replay `log` into it. Returns the new session, or
  throws if any replayed assert fails, because a partially-restored session is
  worse than none: it is a state that never existed."
  ([log] (restore log nil))
  ([log opts]
   (let [session (create-session opts)]
     (try
       (doseq [{:keys [code name]} log]
         (let [reply (assert-rules! session code (when name {:name name}))]
           (when-not (:ok reply)
             (throw (ex-info (str "Replay failed: " (:error reply))
                             {:code code :name name})))))
       session
       (catch Throwable e
         (dispose! session)
         (throw e))))))
