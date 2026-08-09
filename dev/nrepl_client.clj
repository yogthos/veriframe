;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns nrepl-client
  "Drive the running harness from the command line over nREPL.

      jolt -A:dev -M -m nrepl-client '(+ 1 2)'
      echo '(veriframe.system/config)' | jolt -A:dev -M -m nrepl-client -

  It must be `-M -m nrepl-client`. Passing the file path instead loads the
  namespace without calling -main, which exits 0 having printed nothing —
  indistinguishable from a command that ran and returned nil.

  Reads the port from .nrepl-port. Prints stdout from the remote eval, then
  the value (or the exception). This is the same channel an editor uses, so
  anything that works here works from CIDER."
  (:require [clojure.string :as str]
            [nrepl.core :as nrepl]
            [nrepl.middleware]))

(defn- port []
  (or (some-> (try (slurp ".nrepl-port") (catch Throwable _ nil)) str/trim parse-long)
      7888))

(defn -main [& args]
  (let [code (let [a (first args)]
               (if (or (nil? a) (= "-" a)) (slurp *in*) (str/join " " args)))
        t (nrepl/connect "127.0.0.1" (port))]
    (try
      (let [resps (doall (nrepl/message t {:op "eval" :code code}))]
        (doseq [r resps]
          (when-let [o (:out r)] (print o))
          (when-let [e (:err r)] (binding [*out* *err*] (print e))))
        (flush)
        (if-let [ex (some :ex resps)]
          (do (println "ERROR:" ex)
              (doseq [r resps] (when-let [v (:value r)] (println v))))
          (doseq [r resps]
            (when-let [v (:value r)] (println v)))))
      (finally (nrepl/close t)))))
