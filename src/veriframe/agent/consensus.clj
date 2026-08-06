;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.consensus
  "Judge-not-vote: LLM verdicts are evidence, never votes.

  Whenever more than one verdict about the same claim exists, no code path may
  derive an outcome by counting how many judges said PASS or FAIL. The number
  of verifiers who flagged an issue is irrelevant to whether it is real. UCLA's
  FirstProof consensus judge made this its rule — a gap flagged by one verifier
  may be the most important finding, a gap flagged by all of them may still be
  wrong — and their own analysis rated the idea the best one the consensus came
  out of. Majority-counting judge opinions manufactures confidence the judges
  never had: opinions do not compose.

  The one carve-out is mechanical. Independent engine confirmations — Prolog,
  Z3, and Lean each checking the claim — DO compose, because an empirical check
  either holds or it does not, and two independent checks agreeing is evidence.
  `engine-agreement` is the only counting this namespace allows, and it counts
  engine kinds, never judge opinions.

  Aggregation of multiple judge reports goes through `judge-reports`, which
  refuses to run without a single judge. The judge forms its own assessment
  first, receives the reports as evidence rather than as a tally, and must
  record reasoning for each disagreement it accepts or rejects. Every
  aggregation is journalled, so a disagreement that was waved through is a
  question anyone can ask the run about."
  (:require [clojure.string :as str]
            [veriframe.store.journal :as journal]))

;; --- engine confirmation counting --------------------------------------------

(defn- normalize-claim
  "Spelling-level normalization only: case and punctuation. Two artifacts
  claiming the same thing in different casing group together; the claim text
  itself is otherwise untouched."
  [claim]
  (-> (str/lower-case (or claim ""))
      (str/replace #"[^a-z0-9]+" " ")
      str/trim))

(defn engine-agreement
  "For each claim, how many DISTINCT engine kinds confirmed it.

  Counting is correct here, and only here. An engine confirmation is a
  mechanical check of an empirical claim, and two independent engines finding
  the same fact is evidence in a way two judges saying the same thing is not:
  independent empirical checks compose, opinions do not. This is the one
  carve-out in the judge-not-vote contract — the number of verifiers who
  flagged an issue is irrelevant to a judge, but the number of independent
  engines that confirmed a claim is not.

  Pure. Takes confirmed artifacts (maps with :kind :claim :tier, as
  state/confirmed-artifacts returns) and returns {normalized-claim n}. Two Z3
  artifacts for the same claim count once — the count is of distinct engine
  kinds, not artifact rows."
  [artifacts]
  (->> artifacts
       (map (fn [a] [(normalize-claim (:claim a)) (:kind a)]))
       distinct
       (reduce (fn [m [claim kind]] (update m claim (fnil conj #{}) kind)) {})
       (map (fn [[claim kinds]] [claim (count kinds)]))
       (into {})))

;; --- judge aggregation --------------------------------------------------------

(defn- stance
  "A report's position: its verdict and its gap set. Two reports agree when
  both parts match."
  [r]
  [(:verdict r) (set (:gaps r))])

(defn- disagreement-set
  "The reports that are party to a disagreement: their stance differs from some
  other report's. In a split every report is in the set; in a unanimous set none
  is. Nothing here counts how many reports hold each stance — that is the vote
  this namespace exists to forbid."
  [reports]
  (let [stances (mapv stance reports)]
    (set (for [[i r] (map-indexed vector reports)
               :when (some #(not= (nth stances i) %) stances)]
           r))))

(defn- disagreement-view
  "A journal-safe slice of a disagreeing report: enough to name it and its
  position, without dragging the full response text along."
  [r]
  (select-keys r [:source :verdict :gaps]))

(defn judge-reports
  "Aggregate multiple judge reports about the same claim through a single judge.

  THE RULE: verdicts are evidence, never votes. This function has no code path
  that counts how many reports said PASS or FAIL, because no such count is
  meaningful. With one report there is nothing to aggregate and it passes
  through unchanged — no judge call. With two or more, a judge fn is REQUIRED:
  aggregating reports by count is the vote, and it must be unrepresentable, so
  calling this with >= 2 reports and no judge fn throws.

  The judge fn receives the reports and the disagreement set — the reports
  whose verdict or gap list conflicts with another report's — and returns a
  parsed verdict (verdict/parse shape, including :text with the judge's
  reasoning). The result is the judge's verdict augmented with :disagreements
  (the set handed to the judge) and :reasoning (the judge's per-disagreement
  reasoning). Agreement is not an auto-pass: the judge still decides.

  When conn and run-id are supplied, the aggregation is journalled as
  :consensus-judgement with the disagreement set and reasoning; a nil conn
  skips the journal silently."
  ([reports] (judge-reports reports nil))
  ([reports judge-fn] (judge-reports reports judge-fn nil nil))
  ([reports judge-fn conn run-id]
   (let [reports (vec reports)]
     (if (< (count reports) 2)
       (first reports)
       (let [disagreements (disagreement-set reports)]
         (when-not judge-fn
           (throw (ex-info (str "Multiple judge reports with no judge fn: "
                                "aggregating verdicts by count is the vote "
                                "the judge-not-vote contract forbids.")
                           {:reports (count reports)})))
         (let [judgement (judge-fn reports disagreements)
               reasoning (:text judgement)]
           (when (and conn run-id)
             (journal/note! conn run-id :consensus-judgement
                            {:data {:disagreements (mapv disagreement-view
                                                          disagreements)
                                    :reasoning reasoning}}))
           (assoc judgement
                  :disagreements disagreements
                  :reasoning reasoning)))))))
