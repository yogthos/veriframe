;; vf-v2p measurement — one-off replay of this campaign's recorded lean_search
;; calls through the new ranker. Not part of the suite; delete after use.
(ns v2p-replay
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [veriframe.engine.lean-search :as ls]))

(def calls
  (map (fn [{:strs [args result]}]
         {:query (get (json/read-str args) "query")
          :old (cond (str/includes? result "Mathlib matches for") :strong
                     (str/includes? result "Nothing in Mathlib matched") :weak
                     (str/includes? result "No Mathlib declaration matched") :empty
                     :else :other)})
       (json/read-str (slurp ".cache/v2p-calls.json"))))

(defn new-class [q]
  (try
    (let [hits (ls/search {:mathlib-index ".cache/mathlib-index-v2.txt"} q)]
      (cond (empty? hits) :empty
            (some ls/relevant? hits) :strong
            :else :weak))
    (catch Throwable e
      (println "THROW on" (pr-str q) (class e) (.getMessage e))
      :throw)))

(defn -main [& _]
  (let [cfg {:mathlib-index ".cache/mathlib-index-v2.txt"}
        _ (ls/search cfg "warmup") ; populate df-cache once, not per query
        t0 (System/nanoTime)
        results (reduce (fn [acc {:keys [query old]}]
                          (when (zero? (mod (:n acc 0) 50))
                            (println "..." (:n acc 0) (pr-str query)) (flush))
                          (let [t (System/nanoTime)
                                new (try
                                      (let [hits (ls/search cfg query)]
                                        (cond (empty? hits) :empty
                                              (some ls/relevant? hits) :strong
                                              :else :weak))
                                      (catch StackOverflowError _
                                        (println "SOE on" (pr-str query)) :soe)
                                      (catch Throwable e
                                        (println "THROW on" (pr-str query) (.toString e)) :throw))
                                ms (/ (- (System/nanoTime) t) 1e6)]
                            (-> acc
                                (update-in [:trans old new] (fnil inc 0))
                                (update :n inc)
                                (update :ms (fnil max 0) ms))))
                        {} (doall calls))
        total-s (/ (- (System/nanoTime) t0) 1e9)]
    (println "calls:" (:n results) "total-s:" (format "%.1f" total-s)
             "slowest-query-ms:" (int (:ms results)))
    (doseq [[old m] (sort-by (comp str first) (:trans results))
            [new c] (sort-by (comp str first) m)]
      (println (name old) "->" (name new) ":" c))))
