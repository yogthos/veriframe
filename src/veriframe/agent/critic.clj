;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.critic
  "An LLM critic that scores a live branch on explicit objectives, and the
  Pareto machinery the scheduler runs over those scores.

  The cull rule this feeds was a scalar — three consecutive failures, no
  recent confirmation, dead — and three live runs in a row it killed a
  branch the beam arguably wanted: one whose mathematics was right and
  whose Lean proofs merely kept failing. The scores here are judgment
  calls, and are treated as exactly that: the parser fails closed (an
  unusable answer is NO information, never a guessed vector), the
  scheduler falls back to the scalar rule when no scores exist, and every
  score is journaled so a retention decision is auditable after the run.

  Domination — worse-or-equal on every objective, strictly worse on at
  least one — is deliberately the weakest defensible cull criterion. A
  Pareto frontier never discards anything some rational preference could
  still want, so a struggling branch with any unique strength survives.
  The known cost is permissiveness, which is why the reprieve it grants is
  a loan with a clock (the hard floor in the scheduler), not an exemption."
  (:require [clojure.string :as str]
            [veriframe.agent.state :as state]
            [veriframe.agent.verdict :as verdict]
            [veriframe.llm.client :as llm]
            [veriframe.store.journal :as journal]))

(def objectives
  "All maximize, 1..5.

  :progress      engine-confirmed work accumulated over the branch's life
  :momentum      whether the RECENT turns are productive or flailing
  :distinctness  how different this approach is from the sibling theses —
                 the beam's diversity lives here
  :viability     whether the current line has anywhere left to go"
  [:progress :momentum :distinctness :viability])

(defn parse-scores
  "Line-anchored `SCORE <objective>: <1-5>` lines out of a critic response.

  Same discipline as the verdict parser: reasoning is stripped first, prose
  mentioning a score is not a score, several lines for one objective means
  drafts and the last wins, and anything short of all four objectives in
  range returns nil — no information, fail closed."
  [text]
  (let [t (verdict/strip-reasoning text)
        m (into {}
                (keep (fn [line]
                        (when-let [[_ obj v]
                                   (re-matches
                                    #"(?i)SCORE\s+(progress|momentum|distinctness|viability)\s*:\s*([1-5])\s*"
                                    (str/trim line))]
                          [(keyword (str/lower-case obj)) (parse-long v)])))
                (str/split-lines t))]
    (when (= (count objectives) (count m)) m)))

(def survival-objectives
  "The objectives a CULL decision reads: everything except :progress.

  :progress is cumulative, so it rises with age and nothing else. Comparing
  on it lets any mature branch dominate any young one indefinitely — the age
  bias that the juvenile grace period only postpones. It is also the wrong
  question: the artifacts a branch already confirmed are in the log and
  cannot be lost by culling it, so survival should turn on where the line is
  GOING (momentum, viability) and on what it uniquely covers (distinctness).
  A branch that banked a great deal and then stopped moving is exactly the
  one worth reclaiming budget from."
  [:momentum :distinctness :viability])

(defn dominated?
  "True when some sibling is at least as good on every survival objective
  and strictly better on one. Equal vectors do not dominate."
  [scores sibling-scores]
  (boolean
   (some (fn [o]
           (and (every? #(>= (get o % 0) (get scores % 0)) survival-objectives)
                (some #(> (get o % 0) (get scores % 0)) survival-objectives)))
         sibling-scores)))

(defn- summary
  "The deterministic facts the critic judges from. Compact on purpose: the
  critic is a per-branch recurring cost."
  [branch siblings]
  (let [confirmed (state/confirmed-artifacts branch)
        measured (state/empirical-artifacts branch)
        recent-user (->> (:messages branch)
                         (filter #(= "user" (:role %)))
                         (take-last 2)
                         (map #(let [c (str (:content %))]
                                 (subs c 0 (min 400 (count c))))))]
    (str "BRANCH " (:id branch) "\n"
         "Thesis: " (or (get-in branch [:thesis :goal]) "(none registered)") "\n"
         "Turns taken: " (state/turn-count branch)
         "; consecutive failed verifications: "
         (or (:consecutive-failures branch) 0) "\n"
         "Confirmed artifacts: " (count confirmed)
         (when (seq confirmed)
           (str "; the most recent:\n"
                (str/join "\n" (for [a (take-last 3 confirmed)]
                                 (str "  - " (:claim a))))))
         ;; Measurements are listed because leaving them out made a branch
         ;; three hours into a parameter sweep read as a branch that had done
         ;; nothing, and the beam culled it accordingly (vf-0of).
         "\nMeasurements banked: " (count measured)
         (when (seq measured)
           (str "; the most recent:\n"
                (str/join "\n" (for [a (take-last 3 measured)]
                                 (str "  - " (:claim a))))))
         "\n\nSibling theses (the diversity this branch is judged against):\n"
         (if (seq siblings)
           (str/join "\n" (for [s siblings]
                            (str "  - [" (:id s) "] "
                                 (or (get-in s [:thesis :goal]) "(none)"))))
           "  (none — this is the only branch)")
         "\n\nWhat the harness last told the branch:\n"
         (str/join "\n---\n" recent-user))))

(defn score!
  "Score `branch` against its siblings: {:scores {...} :turn turn}, or nil
  when the critic could not answer usably. nil is no information — the
  caller falls back to the scalar rule rather than inventing a vector. A
  usable score is journaled so retention decisions are auditable."
  [{:keys [llm-adapter llm-config conn run-id]} branch siblings turn]
  (let [p (str "You are the research director over parallel proof attempts on"
               " one problem. Score the branch below on four objectives,"
               " 1 (worst) to 5 (best):\n\n"
               "progress: engine-confirmed results accumulated so far, plus"
               " measurements banked. A measurement is not a proof and does not"
               " weigh as one, but a branch that has located a phenomenon"
               " empirically is further along than a branch with neither.\n"
               "momentum: are the RECENT turns productive, or flailing?\n"
               "distinctness: how different is this approach from the sibling"
               " theses? The beam's diversity is worth protecting.\n"
               "viability: does the current line have anywhere left to go, or"
               " is it a dead end regardless of effort?\n\n"
               (summary branch siblings)
               "\n\nEnd your response with exactly four lines:\n"
               "SCORE progress: <n>\nSCORE momentum: <n>\n"
               "SCORE distinctness: <n>\nSCORE viability: <n>")
        scores (try
                 (parse-scores
                  (:content (llm/chat llm-adapter llm-config
                                      [{:role "system"
                                        :content (str "You are a strict research"
                                                      " director. Keep deliberation"
                                                      " brief and end with the four"
                                                      " SCORE lines.")}
                                       {:role "user" :content p}]
                                      {:temperature 0.0})))
                 (catch Throwable _ nil))]
    (when scores
      (when (and conn run-id)
        (journal/note! conn run-id :critic-score
                       {:branch-id (:id branch) :turn turn :data scores}))
      {:scores scores :turn turn})))
