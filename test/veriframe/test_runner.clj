;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.test-runner
  "jolt -M:test — run every test namespace and exit non-zero on failure.

  Also callable from a connected editor as (veriframe.test-runner/run) so the
  suite runs against the live process without paying startup again."
  (:require [clojure.test :as t]
            [veriframe.engine-test]
            [veriframe.agent-test]
            [veriframe.llm-test]
            [veriframe.pool-test]
            [veriframe.prompt-test]
            [veriframe.server-test]
            [veriframe.store-test]
            [veriframe.gui-api-test]
            [veriframe.gui-graph-test]
            [veriframe.gui-style-test]))

(def namespaces
  '[veriframe.store-test
    veriframe.llm-test
    veriframe.agent-test
    veriframe.pool-test
    veriframe.prompt-test
    veriframe.server-test
    veriframe.gui-api-test
    veriframe.gui-graph-test
    veriframe.gui-style-test
    veriframe.engine-test])

(defn run []
  (apply t/run-tests namespaces))

(defn -main [& _]
  (let [{:keys [fail error] :as summary} (run)]
    (println)
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
