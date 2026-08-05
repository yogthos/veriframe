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

(ns veriframe.engine.proc
  "Subprocess helpers shared by the engines.

  Every engine call is bounded. An unbounded one is not a slow call, it is a
  branch that never returns its turn, and with five branches in the beam that
  is the whole run. `run` kills the process tree on timeout rather than
  leaving an orphan holding a pipe."
  (:require [jolt.process :as p]))

(defn run
  "Run `args` with `input` on stdin, capturing stdout and stderr.

  Returns {:exit :out :err} or {:timeout true :ms n} if it did not finish
  inside `timeout-ms`. Never throws on a non-zero exit: z3 exits non-zero
  after `(get-model)` on an unsat formula even though the verdict itself was
  emitted cleanly, so the caller reads the output and decides."
  [{:keys [input timeout-ms]} & args]
  ;; babashka.process/process takes the command vector FIRST and the options
  ;; map second. Passing them the other way round (which is what `sh` accepts)
  ;; stringifies the vector into an argv[0] of "[z3".
  (let [proc (p/process (vec args) {:in (or input "") :out :string :err :string})
        done (deref proc (or timeout-ms 30000) ::timeout)]
    (if (= ::timeout done)
      (do (try (p/destroy-tree proc) (catch Throwable _ nil))
          {:timeout true :ms timeout-ms})
      {:exit (:exit done)
       :out (or (:out done) "")
       :err (or (:err done) "")})))

(defn available?
  "Whether `bin` can be executed at all. Used by the smoke probes and to give
  a clear error instead of a stack trace when a toolchain is missing."
  [bin]
  (try
    (let [{:keys [exit timeout]} (run {:timeout-ms 5000} bin "--version")]
      (and (not timeout) (some? exit)))
    (catch Throwable _ false)))
