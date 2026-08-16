;; Ground-truth probe: does the ranker surface lemmas a branch ACTUALLY used?
;;
;; gen-30 a#836 (TARGET 1 step 3) cites these real Mathlib names in its proof
;; body, so for a query describing what each does, the right answer is known.
;; That is the measurement the floor-clearing replay could not make.
(ns quality-probe
  (:require [veriframe.engine.lean-search :as ls]))

(def cases
  [{:q "last element of a list appended to its dropLast reconstructs the list"
    :want "dropLast_append_getLast"}
   {:q "membership in a list splits it into a prefix and a suffix"
    :want "mem_split"}
   {:q "a chain relation holds on the tail of a cons list"
    :want "Chain'"}
   {:q "getLast of an append when the second list is nonempty"
    :want "getLast?_append"}])

(defn probe [label cfg]
  (println "\n===" label "===")
  (doseq [{:keys [q want]} cases]
    (let [hits (ls/search cfg q 10)
          names (map :n hits)
          rank (first (keep-indexed
                       (fn [i n] (when (re-find (re-pattern want) (str n)) (inc i)))
                       names))]
      (println (format "%-62s want=%-28s %s"
                       (subs q 0 (min 60 (count q)))
                       want
                       (if rank (str "FOUND at rank " rank) "not in top 10")))
      (println "   returned:" (pr-str (vec (take 5 names)))))))

(defn -main [& _]
  (probe "NEW index (names + statements)" {:mathlib-index ".cache/mathlib-index-v2.txt"})
  (probe "OLD index (bare names)" {:mathlib-index ".cache/mathlib-index.txt"}))
