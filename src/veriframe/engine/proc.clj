;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.engine.proc
  "Subprocess helpers shared by the engines.

  Every engine call is bounded. An unbounded one is not a slow call, it is a
  branch that never returns its turn, and with five branches in the beam that
  is the whole run. `run` kills the process tree on timeout rather than
  leaving an orphan holding a pipe."
  (:require [jolt.process :as p]))

(def ^:private sigterm-grace-ms
  "How long a process gets to die of SIGTERM before it is sent SIGKILL."
  2000)

(defn- reap!
  "Kill `proc` and do not return until it is actually gone.

  SIGTERM first, then SIGKILL if it is still alive. `destroy-tree` sends only
  SIGTERM, and the whole point of a timeout is that the process is already not
  behaving, so treating its cooperation as optional is the only honest
  approach. Same principle the rest of the loop follows: a stop path that
  depends on the component agreeing to stop is not a stop path."
  [proc]
  (let [^java.lang.Process p (:proc proc)]
    (try (p/destroy-tree proc) (catch Throwable _ nil))
    (try
      (when-not (.waitFor p sigterm-grace-ms java.util.concurrent.TimeUnit/MILLISECONDS)
        (.destroyForcibly p)
        (.waitFor p sigterm-grace-ms java.util.concurrent.TimeUnit/MILLISECONDS))
      (catch Throwable _ nil))))

(defn run
  "Run `args` with `input` on stdin, capturing stdout and stderr.

  Returns {:exit :out :err} or {:timeout true :ms n} if it did not finish
  inside `timeout-ms`. Never throws on a non-zero exit: z3 exits non-zero
  after `(get-model)` on an unsat formula even though the verdict itself was
  emitted cleanly, so the caller reads the output and decides.

  The wait is `.waitFor` with an explicit timeout rather than a timed `deref`,
  which does not work. jolt's `clojure.core/deref` forwards no opts to a record
  implementing IBlockingDeref, so (deref proc ms ::timeout) silently calls the
  blocking one-arity and waits for however long the process takes. It fails
  quietly, in the direction of doing nothing: the timeout branch below was
  simply unreachable, every engine call was unbounded, and the processes this
  believed it was killing accumulated. Twenty-eight orphaned z3 processes were
  found on one dev machine, the oldest at seventeen hours, slowing everything
  else enough to make an unrelated Mathlib import look sixteen times more
  expensive than it is. Fixed upstream too, but this does not depend on that."
  [{:keys [input timeout-ms]} & args]
  ;; babashka.process/process takes the command vector FIRST and the options
  ;; map second. Passing them the other way round (which is what `sh` accepts)
  ;; stringifies the vector into an argv[0] of "[z3".
  (let [proc (p/process (vec args) {:in (or input "") :out :string :err :string})
        ^java.lang.Process p (:proc proc)
        ms (or timeout-ms 30000)
        finished? (try
                    (.waitFor p ms java.util.concurrent.TimeUnit/MILLISECONDS)
                    (catch Throwable _ false))]
    (if-not finished?
      (do (reap! proc) {:timeout true :ms ms})
      ;; Exited, so this deref returns immediately and only collects the
      ;; already-complete stdout/stderr.
      (let [done @proc]
        {:exit (:exit done)
         :out (or (:out done) "")
         :err (or (:err done) "")}))))

(defn available?
  "Whether `bin` can be executed at all. Used by the smoke probes and to give
  a clear error instead of a stack trace when a toolchain is missing."
  [bin]
  (try
    (let [{:keys [exit timeout]} (run {:timeout-ms 5000} bin "--version")]
      (and (not timeout) (some? exit)))
    (catch Throwable _ false)))
