;; find which recorded query kills search
(ns v2p-probe
  (:require [clojure.data.json :as json]
            [veriframe.engine.lean-search :as ls]))

(defn -main [& _]
  (let [cfg {:mathlib-index ".cache/mathlib-index-v2.txt"}
        _ (ls/search cfg "warmup")
        calls (json/read-str (slurp ".cache/v2p-calls.json"))
        qs (distinct (map #(get (json/read-str (get % "args")) "query") calls))]
    (println "distinct queries:" (count qs))
    (doseq [[i q] (map-indexed vector qs)]
      (print i " ") (flush)
      (let [hits (try (ls/search cfg q)
                      (catch Throwable e
                        (println) (println "DIED on" (pr-str q) (.toString e)) (System/exit 1)))]
        (when (some ls/relevant? hits) (print "*")))
      (flush)))
  (println) (println "survived all"))
