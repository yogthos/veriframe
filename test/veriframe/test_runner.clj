(ns veriframe.test-runner
  "jolt -M:test — run every test namespace and exit non-zero on failure.

  Also callable from a connected editor as (veriframe.test-runner/run) so the
  suite runs against the live process without paying startup again."
  (:require [clojure.test :as t]
            [veriframe.engine-test]
            [veriframe.agent-test]
            [veriframe.llm-test]
            [veriframe.prompt-test]
            [veriframe.store-test]))

(def namespaces
  '[veriframe.store-test
    veriframe.llm-test
    veriframe.agent-test
    veriframe.prompt-test
    veriframe.engine-test])

(defn run []
  (apply t/run-tests namespaces))

(defn -main [& _]
  (let [{:keys [fail error] :as summary} (run)]
    (println)
    (println summary)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))
