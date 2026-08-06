;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.tools
  "Tool dispatch, one method per tool.

  A multimethod rather than a case, because it is what lets a tool be
  redefined against a running process and picked up on the next branch turn.
  That is the tightest loop available for the part of the system that changes
  most.

  Every method takes a context and returns a result map:

    {:result   string the model sees
     :category :success | :failure | :neutral   what the cull guard reads
     :progress? bool                            what the stall guard reads
     :branch   the updated branch
     :artifact optional, recorded to the artifacts table
     :failure  optional, recorded to the shared failure log
     :done?    optional, ends the run}

  :category and :progress? are separate on purpose. A tool call can succeed and
  advance nothing, and a model making varied, well-formed, useless calls trips
  no error-keyed guard while burning the whole run."
  (:require [clojure.string :as str]
            [veriframe.agent.state :as state]
            [veriframe.agent.verdict :as verdict]
            [veriframe.engine.lean-pool :as lean-pool]
            [veriframe.engine.lean-repl :as lean-repl]
            [veriframe.engine.lean-search :as lean-search]
            [veriframe.engine.lint :as lint]
            [veriframe.engine.octave :as octave]
            [veriframe.engine.prolog :as prolog]
            [veriframe.engine.smt :as smt]
            [veriframe.engine.smt-templates :as templates]
            [veriframe.llm.client :as llm]
            [veriframe.store.journal :as journal]))

(defmulti run-tool
  (fn [ctx] (:tool-name ctx)))

(defn- ok [branch result & {:as extra}]
  (merge {:result result :category :neutral :progress? false :branch branch} extra))

(defn- fail [branch result & {:as extra}]
  (merge {:result result :category :failure :progress? false :branch branch} extra))

(defn- arg [ctx k] (get-in ctx [:args k]))

(defn- patches-section
  "The repairs a judge carried, as a `PATCHES:` block appended to its result
  so the branch's next turn — a fresh model call — has the fixes spelled out
  even when the judge's prose buried them."
  [minors]
  (when (seq minors)
    (str "\n\nPATCHES:\n"
         (str/join "\n" (for [m minors]
                          (str "- " (:description m) " → " (:patch m)))))))

(defn- patchable-suffix
  "The ` — N patchable defect(s) listed` tail for a failure reason, when the
  judge carried fixes."
  [minors]
  (when (seq minors)
    (let [n (count minors)]
      (str " — " n " patchable defect" (when (> n 1) "s") " listed"))))

(defn- missing [ctx & ks]
  (let [absent (remove #(let [v (arg ctx %)]
                          (and (some? v) (not (and (string? v) (str/blank? v)))))
                       ks)]
    (when (seq absent)
      (str "Missing required argument(s): " (str/join ", " (map name absent)) "."))))

;; --- Prolog -----------------------------------------------------------------

(defmethod run-tool "add_rule" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :code)]
    (fail branch m)
    (let [name (arg ctx :name)
          reply (prolog/assert-rules! (:prolog branch) (arg ctx :code)
                                      (when name {:name name}))]
      (if (:ok reply)
        (ok branch (str "Loaded " (:clauses reply) " clause(s)."
                        (if name
                          (str " Named `" name "` — retractable with retract_rule.")
                          " Anonymous, so permanent for this branch."))
            :progress? true)
        (fail branch (str "Prolog refused the program:\n" (:error reply)))))))

(defmethod run-tool "retract_rule" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :name)]
    (fail branch m)
    (let [name (arg ctx :name)
          depended (filter #(and (= :prolog (:kind %))
                                 (= :confirmed (:claim-status %))
                                 (contains? (set (:rules %)) name))
                           (:artifacts branch))]
      (cond
        ;; The publish-state guard. Discarding the rules a confirmed artifact
        ;; rests on invalidates the artifact without saying so, and three of
        ;; the four winning iterations in the AHE paper were this one failure
        ;; family. Blocks discarding, never editing.
        (seq depended)
        (fail branch
              (str "Refused: rule `" name "` is what confirmed "
                   (str/join "; " (map :claim depended))
                   ". Retracting it would silently invalidate a result you have"
                   " already banked. Add a corrected rule under a new name"
                   " instead, or call give_up if the result was wrong."))

        :else
        (let [reply (prolog/retract-rule! (:prolog branch) name)]
          (if (:ok reply)
            (ok branch (str "Erased " (:erased reply) " clause(s) named `" name "`."))
            (fail branch (str "Retract failed: " (:error reply)))))))))

(defmethod run-tool "verify" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :claim :check)]
    (fail branch m)
    (let [claim (arg ctx :claim)
          reply (prolog/query (:prolog branch) (arg ctx :check) {:timeout-s 20})]
      (cond
        (not (:ok reply))
        (fail branch (str "The goal did not run: " (:error reply)
                          "\nThat is an encoding problem, not evidence about the claim.")
              :failure {:claim claim :reason (:error reply)})

        (empty? (:answers reply))
        (fail branch (str "The goal FAILED. Prolog found no solution, so the claim"
                          " `" claim "` is not supported by the current rules.")
              :failure {:claim claim :reason "the Prolog goal failed"})

        :else
        (let [answers (:answers reply)
              shown (take 10 answers)
              bindings (mapv :bindings shown)
              ;; A goal can succeed while leaving the variables the claim is
              ;; about unbound — `findall([A,B,C], …, Sols)` succeeds with A, B
              ;; and C still fresh. This is the Prolog analogue of a SAT verdict
              ;; over free variables: it proves the goal is satisfiable, not
              ;; that any particular assignment is the answer. Observed on the
              ;; first live run, where a confirmed artifact's witness read
              ;; `A = _13726`.
              unbound? (fn [v] (boolean (re-matches #"_G?\d+" (str v))))
              all-unbound? (and (seq bindings)
                                (every? #(and (seq %) (every? (comp unbound? val) %))
                                        bindings))
              some-unbound (when (seq bindings)
                             (->> (first bindings) (filter (comp unbound? val)) (map key)))
              status (if all-unbound? :existential :confirmed)]
          {:branch branch
           :category (if all-unbound? :failure :success)
           :progress? (not all-unbound?)
           :result (str "The goal succeeded with " (count answers)
                        (if (:truncated reply) "+ (truncated)" "") " solution(s):\n"
                        (str/join "\n" (map #(str "  " (:formatted %)) shown))
                        (cond
                          all-unbound?
                          (str "\n\nEvery variable came back unbound, so this says the goal"
                               " is satisfiable and nothing about which assignment holds."
                               " It does NOT confirm your claim. Bind the variables the"
                               " claim is about, or query them directly.")
                          (seq some-unbound)
                          (str "\n\nNote: " (str/join ", " (map name some-unbound))
                               " came back unbound, so the claim rests on the variables"
                               " that did bind.")
                          :else ""))
           :artifact {:kind :prolog :claim claim :code (arg ctx :check)
                      :claim-status status :tier :fast :witness bindings}})))))

;; --- Z3 ---------------------------------------------------------------------

(defn- smt-claim-status
  "Map a Z3 verdict onto what it says about the claim.

  `expected-verdict` is the model declaring, before the run, which verdict
  supports its claim. Without it the harness genuinely cannot tell: under one
  encoding UNSAT means confirmed (the negation is unsatisfiable) and under
  another it means refuted (the claim itself is unsatisfiable). Absent the
  declaration the artifact is :ambiguous and cannot substantiate an answer."
  [verdict expected free-vars?]
  (cond
    (= :unknown verdict) :ambiguous
    (nil? expected) :ambiguous
    ;; A SAT verdict over free variables says a solution exists. It does not
    ;; hand you one, and models routinely confuse the two.
    (and (= :sat verdict) (= :sat expected) free-vars?) :existential
    (= (name verdict) (name expected)) :confirmed
    :else :refuted))

(defn free-variables
  "Declared constants the formula never pins to a literal value.

  A SAT result over these is Z3 choosing values to satisfy the constraints,
  not a witness the model specified."
  [smtlib]
  (let [declared (set (map second (re-seq #"\(\s*declare-(?:const|fun)\s+([^\s()]+)" smtlib)))
        pinned (set (map second (re-seq #"\(\s*=\s+([A-Za-z_][\w-]*)\s+-?\d" smtlib)))]
    (vec (sort (remove pinned declared)))))

(defmethod run-tool "verify_smt" [{:keys [branch config] :as ctx}]
  (if-let [m (missing ctx :claim :smtlib)]
    (fail branch m)
    (let [claim (arg ctx :claim)
          smtlib (arg ctx :smtlib)
          expected (some-> (arg ctx :expectedVerdict) str/lower-case keyword)
          r (smt/run-smt smtlib (get-in config [:engines :z3]))]
      (if (= :error (:status r))
        (fail branch (:error r) :failure {:claim claim :reason (:error r)})
        (let [free (free-variables smtlib)
              status (smt-claim-status (:verdict r) expected (seq free))]
          (merge
           {:branch branch
            :category (if (= :confirmed status) :success :failure)
            :progress? (= :confirmed status)
            :result (str "Z3 says " (name (:verdict r)) ". "
                         (case status
                           :confirmed "That matches your expectedVerdict — claim CONFIRMED."
                           :refuted "That contradicts your expectedVerdict — claim REFUTED."
                           :existential
                           (str "You expected SAT and got it, but " (str/join ", " free)
                                " are free, so Z3 chose values to satisfy your constraints."
                                " This proves a solution EXISTS; it does not certify the"
                                " one you have in mind. Pin the values with (assert (= x N))"
                                " and re-run if you mean to claim a specific witness.")
                           :ambiguous
                           (if (nil? expected)
                             (str "You did not declare expectedVerdict, so the harness cannot"
                                  " tell whether this verdict supports or refutes your claim."
                                  " Pass expectedVerdict as \"sat\" or \"unsat\".")
                             "Z3 returned UNKNOWN, which is not evidence either way."))
                         (when (:model r)
                           (str "\nWitness: " (pr-str (:model r)))))
            :artifact {:kind :smt :claim claim :code smtlib :verdict (:verdict r)
                       :witness (:model r) :claim-status status :tier :fast}}
           (when-not (= :confirmed status)
             {:failure {:claim claim
                        :reason (str "z3 returned " (name (:verdict r))
                                     " (status " (name status) ")")}})))))))

(defmethod run-tool "verify_template" [{:keys [branch config] :as ctx}]
  (if-let [m (missing ctx :claim :template)]
    (fail branch m)
    (let [claim (arg ctx :claim)
          tname (arg ctx :template)
          slots (or (arg ctx :slots) {})
          r (smt/run-template tname slots (get-in config [:engines :z3]))]
      (if (= :error (:status r))
        (fail branch (:error r) :failure {:claim claim :reason (:error r)})
        (let [status (cond (:confirmed r) :confirmed
                           (:agreed r) :refuted
                           :else :ambiguous)]
          (merge
           {:branch (update branch :tiers-seen conj :slow)
            :category (if (:confirmed r) :success :failure)
            :progress? (:confirmed r)
            :result (str (:note r)
                         "\n  primary   " (name (get-in r [:primary :verdict]))
                         " (expected " (name (get-in r [:primary :expected])) ")"
                         "\n  crosscheck " (name (get-in r [:cross :verdict]))
                         " (expected " (name (get-in r [:cross :expected])) ")"
                         (when (:confirmed r)
                           "\n\nBoth encodings agree, so this needs no separate review."))
            :artifact {:kind :smt :claim claim
                       :code (get-in r [:primary :smtlib])
                       :verdict (get-in r [:primary :verdict])
                       :witness (get-in r [:primary :model])
                       :claim-status status :tier :slow}}
           (when-not (:confirmed r)
             {:failure {:claim claim :reason (:note r)}})))))))

;; --- planning ---------------------------------------------------------------

(defmethod run-tool "thesis" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :goal :technique)]
    (fail branch m)
    (let [thesis {:goal (arg ctx :goal)
                  :subClaims (vec (or (arg ctx :subClaims) []))
                  :technique (arg ctx :technique)
                  :nonFiniteJustification (arg ctx :nonFiniteJustification)
                  :set-at-turn (:turn ctx)}]
      (ok (assoc branch :thesis thesis)
          (str "Thesis registered: " (:goal thesis)
               "\nTechnique: " (:technique thesis)
               (when (seq (:subClaims thesis))
                 (str "\nSub-claims:\n"
                      (str/join "\n" (map-indexed #(str "  " (inc %1) ". " %2)
                                                  (:subClaims thesis)))))
               "\n\nThe audit gate cross-references this against what you actually"
               " verified, so a general claim backed only by small instances will"
               " be caught here.")
          :progress? true
          :thesis thesis))))

;; --- sub-LLM judgements -----------------------------------------------------

(defn- judge
  "Ask the model a yes-or-no question and read the answer through the
  constrained parser. Any failure to answer cleanly fails closed."
  [{:keys [llm-adapter llm-config]} prompt]
  (try
    (let [r (llm/chat llm-adapter llm-config
                      [{:role "system" :content (str "You are a strict reviewer. "
                                                     verdict/instruction)}
                       {:role "user" :content prompt}]
                      {:temperature 0.0})
          parsed (verdict/parse (:content r))]
      ;; The reasoning stream is stripped HERE, not at the call sites, because
      ;; every caller quotes :text back into the branch's message history and a
      ;; branch that reads reviewer-voice reasoning answers its next turn as a
      ;; reviewer instead of calling a tool. Keeping the raw text in the map
      ;; would leave that trap set for the next caller added.
      (assoc parsed :text (verdict/strip-reasoning (:content r))))
    (catch Throwable e
      {:verdict :unparseable :reason (str "the judge call failed: " (ex-message e))})))

(defmethod run-tool "review" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :claim :rationale)]
    (fail branch m)
    (let [claim (arg ctx :claim)
          confirmed (state/confirmed-artifacts branch)]
      (if (empty? confirmed)
        (fail branch (str "Nothing to review: this branch has no confirmed artifact."
                          " Verify something first."))
        (let [artifact (last confirmed)
              p (str "A harness verified this claim and is about to ship it.\n\n"
                     "CLAIM: " claim "\n\n"
                     "ORIGINAL ENCODING (" (name (:kind artifact)) "):\n"
                     (:code artifact) "\n\n"
                     "The author says their cross-check is independent because:\n"
                     (arg ctx :rationale) "\n\n"
                     "Answer PASS only if the cross-check really is independent in"
                     " SHAPE — a different formulation of the same question, not the"
                     " same encoding rewritten — and the claim follows from it."
                     " Answer FAIL if the two encodings share the assumption that"
                     " could be wrong.")
              j (judge ctx p)
              passed (verdict/passed? j)
              _ (when-let [d (:disagreement j)]
                  (when (and (:conn ctx) (:run-id ctx))
                    (journal/note! (:conn ctx) (:run-id ctx)
                                   :verdict-gap-disagreement
                                   {:branch-id (:id branch)
                                    :data {:tool "review"
                                           :disagreement (name d)}})))]
          (merge
           {:branch (-> branch
                        (assoc :last-review {:passed passed :claim claim
                                             :rationale (arg ctx :rationale)})
                        (update :tiers-seen conj :slow))
            :category (if passed :success :failure)
            :progress? passed
            :result (str "Review verdict: " (name (:verdict j))
                         (when (:reason j) (str " — " (:reason j)))
                         "\n\n" (or (:text j) "")
                         (patches-section (:minors j)))}
           (when-not passed
             {:failure {:claim claim
                        :reason (str "review returned " (name (:verdict j))
                                     (patchable-suffix (:minors j)))}})))))))

(defmethod run-tool "audit" [{:keys [branch] :as ctx}]
  (cond
    (missing ctx :claim :proposedAnswer)
    (fail branch (missing ctx :claim :proposedAnswer))

    ;; The auditor cross-references the thesis against what was verified, so
    ;; without a thesis it has nothing to check the claim's scope against.
    ;; This is what catches "claimed a general proof, verified small cases".
    (nil? (:thesis branch))
    (fail branch (str "Refused: no thesis registered. Call `thesis` first so the"
                      " audit can check whether what you verified actually"
                      " establishes what you set out to prove."))

    :else
    (let [claim (arg ctx :claim)
          answer (arg ctx :proposedAnswer)
          confirmed (state/confirmed-artifacts branch)
          p (str "A harness is about to ship an answer. Decide whether the verified"
                 " evidence actually establishes it.\n\n"
                 "THESIS: " (get-in branch [:thesis :goal]) "\n"
                 "TECHNIQUE: " (get-in branch [:thesis :technique]) "\n"
                 "SUB-CLAIMS: " (pr-str (get-in branch [:thesis :subClaims])) "\n\n"
                 "PROPOSED ANSWER: " answer "\n"
                 "CLAIM: " claim "\n\n"
                 "CONFIRMED ARTIFACTS (" (count confirmed) "):\n"
                 (str/join "\n" (for [a confirmed]
                                  (str "- [" (name (:kind a)) "/" (name (:tier a)) "] "
                                       (:claim a))))
                 "\n\nAnswer FAIL if the artifacts verify only instances of a claim"
                 " stated universally, if the proposed answer asserts anything no"
                 " artifact covers, or if the thesis and the evidence are about"
                 " different things. Answer PASS only if the evidence establishes"
                 " the answer as stated.")
          j (judge ctx p)
          passed (verdict/passed? j)
          _ (when-let [d (:disagreement j)]
              (when (and (:conn ctx) (:run-id ctx))
                (journal/note! (:conn ctx) (:run-id ctx)
                               :verdict-gap-disagreement
                               {:branch-id (:id branch)
                                :data {:tool "audit"
                                       :disagreement (name d)}})))]
      (merge
       {:branch (assoc branch :last-audit {:passed passed :proposed-answer answer})
        :category (if passed :success :failure)
        :progress? false
        :result (str "Audit verdict: " (name (:verdict j))
                     (when (:reason j) (str " — " (:reason j)))
                     "\n\n" (or (:text j) "")
                     (patches-section (:minors j))
                     (when passed
                       "\n\nThe done gate will accept this answer verbatim."))}
       (when-not passed
         {:failure {:claim claim
                    :reason (str "audit returned " (name (:verdict j))
                                 (patchable-suffix (:minors j)))}})))))

;; --- the done gate ----------------------------------------------------------

(def ^:private stopwords
  ;; Grammar plus the vocabulary a model uses to FRAME an answer rather than to
  ;; assert one. The gate is aimed at specifics — numbers, names, witnesses —
  ;; because that is where fabrication actually happens; "the answer is" is not
  ;; a claim about anything. Widening this list weakens the gate, so entries
  ;; earn their place by being framing rather than content.
  #{"the" "a" "an" "is" "are" "was" "were" "of" "for" "and" "or" "not" "no"
    "in" "on" "to" "with" "that" "this" "it" "as" "by" "at" "be" "there"
    "exists" "all" "any" "we" "have" "has" "can" "so" "if" "then" "thus"
    "answer" "solution" "solutions" "result" "results" "value" "values"
    "therefore" "hence" "conclusion" "shows" "show" "proved" "proven"
    "verified" "confirms" "confirmed" "follows" "given" "which" "where"
    "unique" "uniquely" "only" "exactly" "such" "these" "those" "each"
    "every" "must" "also" "both" "same" "case" "cases" "holds" "true" "false"

    ;; Provenance vocabulary: how the answer was checked, not what it claims.
    ;; These can never appear in an artifact, because an artifact's claim and
    ;; code are about the problem and say nothing about the engine that ran
    ;; them. Leaving them in made the gate refuse answers for asserting the
    ;; word "mathlib", which pushed the model toward stripping every
    ;; explanatory sentence to get past it — the opposite of what a
    ;; verification harness wants its answers to look like. Observed costing
    ;; three turns on one run.
    "lean" "mathlib" "prolog" "clpfd" "swipl" "z3" "smt" "smtlib" "octave"
    "engine" "engines" "harness" "kernel" "kernel-checked" "machine-checked"
    "theorem" "theorems" "lemma" "lemmas" "proof" "proofs" "tactic" "tactics"
    "statement" "statements" "universal" "universally" "induction" "inductive"
    "base" "step" "successor" "encoding" "encodings" "formalisation"
    "formalization" "independent" "independently" "cross-check" "cross-checked"
    ;; Generic mathematical prose. "equals" and "numbers" carry no specific
    ;; content — the specific part is the number or name they connect.
    "number" "numbers" "equal" "equals" "integer" "integers" "natural"
    "naturals" "first" "sums" "pairwise" "distinct" "positive"})

;; A tool name followed by its version. Stripped BEFORE tokenizing, because the
;; version is a bare number and numbers are the part of this gate that must not
;; be relaxed — "Lean 4" would otherwise assert the number 4 and get refused for
;; it. Narrow on purpose: only a number directly after a known engine name.
(def ^:private tool-version-re
  #"(?i)\b(lean|mathlib|z3|swipl|swi-prolog|prolog|octave|python)[\s-]*[0-9]+(\.[0-9]+)*")

(defn answer-tokens
  "Substantive tokens from a proposed answer: numbers and words that are not
  stopwords. Numbers matter most — an answer naming a size, a bound, or a
  witness has to have that number in the evidence."
  [text]
  (->> (str/split (str/lower-case (str/replace (or text "") tool-version-re " "))
                  #"[^a-z0-9_.-]+")
       ;; `.` and `-` stay INSIDE the split class so 3.5 and cross-check survive
       ;; as one token, which means a sentence-final period rides along with the
       ;; last word. "successor." then matches no stopword and gets reported as
       ;; an unsupported assertion. Trim the edges, keep the interior.
       (map #(str/replace % #"^[.-]+|[.-]+$" ""))
       (remove str/blank?)
       (remove stopwords)
       (filter #(or (re-matches #"[0-9]+(\.[0-9]+)?" %) (>= (count %) 4)))
       distinct))

(defn uncovered-tokens
  "Answer tokens no confirmed artifact mentions.

  The claim-evidence gate, deterministic and with no model in the path. An
  answer asserting a number that appears nowhere in the evidence is a
  fabricated verification report, which is the failure dirge PR 749 was
  written for."
  [answer artifacts]
  (let [haystack (str/lower-case
                  (str/join " " (for [a artifacts]
                                  (str (:claim a) " " (:code a) " " (pr-str (:witness a))))))]
    (remove #(str/includes? haystack %) (answer-tokens answer))))

(defmethod run-tool "done" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :answer)]
    (fail branch m)
    (let [answer (arg ctx :answer)
          confirmed (state/confirmed-artifacts branch)
          audit (:last-audit branch)
          review (:last-review branch)
          template-confirmed? (some #(and (= :slow (:tier %))
                                          (= :confirmed (:claim-status %)))
                                    (:artifacts branch))
          uncovered (uncovered-tokens answer confirmed)
          block (cond
                  (empty? confirmed)
                  "This branch has no confirmed artifact. Nothing has been verified."

                  (not (and audit (:passed audit)))
                  (str "The pre-ship audit has not passed. Call `audit` with"
                       " {claim, proposedAnswer} first.")

                  (not= (:proposed-answer audit) answer)
                  (str "The audit passed for a different answer. It approved:\n  "
                       (:proposed-answer audit)
                       "\nand you are shipping:\n  " answer
                       "\nRe-run `audit` against the answer you actually intend to ship.")

                  (and (not review) (not template-confirmed?))
                  (str "Nothing has been independently cross-checked. Either run"
                       " `review` with an encoding different in shape from the one"
                       " that confirmed the result, or use `verify_template`, whose"
                       " cross-check is built in.")

                  (and review (not (:passed review)) (not template-confirmed?))
                  "The last review FAILED. Resolve the disagreement before shipping."

                  (seq uncovered)
                  (str "Your answer asserts things no confirmed artifact supports: "
                       (str/join ", " (map #(str "`" % "`") (take 8 uncovered)))
                       ".\nEvery substantive claim in the answer has to appear in"
                       " something an engine confirmed. Either verify these or"
                       " remove them from the answer."))]
      (if block
        (fail branch (str "`done` refused.\n\n" block) :done-block block)
        {:branch (assoc branch :final-answer answer :status :done)
         :category :success
         :progress? true
         :done? true
         :answer answer
         :result (str "Answer accepted.\n\n" answer)}))))

(defmethod run-tool "give_up" [{:keys [branch] :as ctx}]
  (let [reason (or (arg ctx :reason) "no reason given")]
    {:branch (assoc branch :status :abandoned :inactive-reason reason)
     :category :neutral :progress? false :gave-up? true
     :result (str "Gave up: " reason)}))

;; --- unknown ----------------------------------------------------------------

(defmethod run-tool :default [{:keys [branch tool-name]}]
  (fail (update-in branch [:mechanics :unknown-tools] inc)
        (str "No tool named `" tool-name "`. Available: "
             (str/join ", " (sort (remove #{:default} (keys (methods run-tool)))))
             ".")))

(defn tool-names []
  (sort (remove keyword? (keys (methods run-tool)))))

;; --- forking ----------------------------------------------------------------

(def max-branch-theses 4)

(defmethod run-tool "branch_theses" [{:keys [branch] :as ctx}]
  (let [proposals (arg ctx :theses)]
    (cond
      (or (not (sequential? proposals)) (empty? proposals))
      (fail branch (str "`theses` must be a non-empty array of"
                        " {goal, subClaims, technique} objects."))

      (> (count proposals) max-branch-theses)
      (fail branch (str "At most " max-branch-theses " theses per call; you proposed "
                        (count proposals) "."))

      (not (every? #(and (map? %) (string? (:goal %))) proposals))
      (fail branch "Every thesis must be an object with a `goal` string.")

      :else
      ;; The first commits THIS branch; the rest become siblings. The scheduler
      ;; reads :pending-branch-theses after the turn and clears it, so a tool
      ;; never creates a branch itself — one place owns the branch table.
      (let [[mine & others] proposals
            thesis (assoc mine :set-at-turn (:turn ctx))]
        (ok (assoc branch :thesis thesis
                   :pending-branch-theses (vec others))
            (str "Committed to: " (:goal thesis)
                 (when (seq others)
                   (str "\nRequested " (count others) " sibling branch(es) for: "
                        (str/join "; " (map :goal others))
                        "\nThey explore independently and share this branch's"
                        " failure log, so none of you will repeat another's"
                        " dead end."))) 
            :progress? true
            :thesis thesis)))))

;; --- Lean -------------------------------------------------------------------
;;
;; The Lean session is acquired LAZILY, on the first Lean tool call, because
;; most problems never touch Lean and a session that imported Mathlib holds a
;; lot of memory.
;;
;; Lazy does not have to mean cold, though, which is what it used to mean: the
;; import measured 377927ms against a 420000ms turn deadline, so the first Lean
;; call spent essentially the whole turn importing and then blew the deadline.
;; veriframe.engine.lean-pool warms sessions at startup, so this usually hands
;; back one that is already at the Mathlib environment. The fallback of building
;; one here stays, so an empty or failed pool costs latency and not the tool.

(defn- lean-session!
  "The branch's Lean session: a warmed one if the pool has it, otherwise a fresh
  import. Returns [session branch].

  Waits briefly on a slot whose import is still running rather than starting a
  second import alongside it — two concurrent imports are slower than one, so
  racing the pool would be worse than either warming or not warming."
  [{:keys [branch config]}]
  (if-let [s (:lean branch)]
    [s branch]
    (let [s (or (lean-pool/checkout! (get-in config [:warmup :checkout-wait-ms] 60000))
                (doto (lean-repl/create-session (get-in config [:engines :lean]))
                  (lean-repl/mathlib-env)))]
      [s (assoc branch :lean s)])))

(defn- lean-error-text [errors]
  (str/join "\n" (map #(str "  " (str/replace (str (:data %)) "\n" "\n  ")) errors)))

(defmethod run-tool "verify_lean" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :claim :lean)]
    (fail branch m)
    (let [{:keys [ok warnings]} (lint/lint-lean (arg ctx :lean))]
      (if-not ok
        ;; `sorry` compiles with a warning, so without the lint a snippet that
        ;; proves nothing would be recorded as confirmed. Observed in the
        ;; Frankl run in the original harness.
        (fail branch (str "Lean lint rejected the snippet — nothing was run:\n  • "
                          (str/join "\n  • " warnings)))
        (try
          (let [[s branch] (lean-session! ctx)
                claim (arg ctx :claim)
                r (lean-repl/run-command s (arg ctx :lean))]
            (cond
              (:error r)
              (fail branch (str "The Lean REPL failed: " (:error r))
                    :failure {:claim claim :reason (:error r)})

              (seq (:sorries r))
              (fail branch
                    (str "The snippet elaborated but left " (count (:sorries r))
                         " `sorry` goal(s) open, so it proves nothing. Close them,"
                         " or use proof_start to develop the proof step by step.")
                    :failure {:claim claim :reason "the proof contained sorry"})

              (:ok r)
              {:branch branch :category :success :progress? true
               :result (str "Lean accepted it. Claim CONFIRMED.")
               :artifact {:kind :lean :claim claim :code (arg ctx :lean)
                          :claim-status :confirmed :tier :fast}}

              :else
              (fail branch (str "Lean rejected it:\n" (lean-error-text (:errors r)))
                    :failure {:claim claim
                              :reason (str "lean: " (some-> (first (:errors r)) :data
                                                            (str/replace #"\s+" " ")
                                                            (subs 0 (min 160 (count (str (:data (first (:errors r)))))))))}
                    :artifact {:kind :lean :claim claim :code (arg ctx :lean)
                               :claim-status :refuted :tier :fast})))
          (catch Throwable e
            (fail branch (str "Lean is unavailable: " (ex-message e)))))))))

(defmethod run-tool "lean_search" [{:keys [branch config] :as ctx}]
  (if-let [m (missing ctx :query)]
    (fail branch m)
    (try
      (let [q (arg ctx :query)
            hits (lean-search/search (get-in config [:engines :lean]) q
                                     (or (arg ctx :top_k) 10))]
        (ok branch (lean-search/render hits q)))
      (catch Throwable e
        (fail branch (str "Mathlib search is unavailable: " (ex-message e)))))))

(defmethod run-tool "proof_start" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :claim :theorem)]
    (fail branch m)
    (try
      (let [[s branch] (lean-session! ctx)
            stmt (str (arg ctx :theorem) " := by sorry")
            r (lean-repl/run-command s stmt)]
        (if-let [sorry (first (:sorries r))]
          (ok (assoc branch :proof {:claim (arg ctx :claim)
                                    :theorem (arg ctx :theorem)
                                    :state (:proofState sorry)
                                    :tactics []})
              (str "Proof opened. Goal:\n\n" (:goal sorry)
                   "\n\nApply one tactic at a time with proof_step."))
          (fail branch
                (str "The theorem statement did not elaborate:\n"
                     (lean-error-text (:errors r))))))
      (catch Throwable e
        (fail branch (str "Lean is unavailable: " (ex-message e)))))))

(defmethod run-tool "proof_step" [{:keys [branch] :as ctx}]
  (cond
    (missing ctx :tactic) (fail branch (missing ctx :tactic))
    (nil? (:proof branch)) (fail branch "No proof is open. Call proof_start first.")
    :else
    (let [s (:lean branch)
          p (:proof branch)
          r (lean-repl/apply-tactic s (arg ctx :tactic) (:state p))]
      (cond
        (:error r)
        (fail branch (str "The Lean REPL failed: " (:error r)))

        (not (:ok r))
        ;; The state is NOT advanced on a failed tactic, so the branch can try
        ;; another without unwinding.
        (fail branch (str "The tactic failed; the goal is unchanged:\n"
                          (lean-error-text (:errors r))))

        (:closed? r)
        (let [p (update p :tactics conj (arg ctx :tactic))]
          {:branch (assoc branch :proof (assoc p :state (:proof-state r) :closed? true))
           :category :success :progress? true
           :result (str "No goals remain — the proof is CLOSED.\n\n"
                        (:theorem p) " := by\n  "
                        (str/join "\n  " (:tactics p)))
           :artifact {:kind :lean :claim (:claim p)
                      :code (str (:theorem p) " := by\n  "
                                 (str/join "\n  " (:tactics p)))
                      :claim-status :confirmed :tier :slow}})

        :else
        (ok (assoc branch :proof (-> p
                                     (assoc :state (:proof-state r))
                                     (update :tactics conj (arg ctx :tactic))))
            (str (count (:goals r)) " goal(s) remain:\n\n"
                 (str/join "\n\n" (:goals r)))
            :progress? true)))))

(defmethod run-tool "proof_state" [{:keys [branch]}]
  (if-let [p (:proof branch)]
    (ok branch (str "Proving: " (:theorem p)
                    "\nTactics so far:\n  " (str/join "\n  " (:tactics p))))
    (fail branch "No proof is open.")))

(defmethod run-tool "proof_abandon" [{:keys [branch]}]
  (ok (dissoc branch :proof) "Proof abandoned."))

;; --- Octave -----------------------------------------------------------------
;; Lazy per branch, like Lean: most problems are not numerical and a workspace
;; costs a directory and a process per call.

(defn- octave-session!
  "The branch's Octave workspace, created on first use. Returns [session branch]."
  [{:keys [branch config]}]
  (if-let [s (:octave branch)]
    [s branch]
    (let [s (octave/create-session (get-in config [:engines :octave]))]
      [s (assoc branch :octave s)])))

(defmethod run-tool "octave_eval" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :code)]
    (fail branch m)
    (try
      (let [[s branch] (octave-session! ctx)
            r (octave/eval-code! s (arg ctx :code))]
        (if (:ok r)
          (ok branch (str "Ran it. Workspace updated."
                          (when-not (str/blank? (str (:output r)))
                            (str "\n\n" (:output r))))
              :progress? true)
          (fail branch (str "Octave rejected it:\n" (:error r)))))
      (catch Throwable e
        (fail branch (str "Octave is unavailable: " (ex-message e)))))))

(defn- octave-claim-text
  "How the claim is recorded. An approximate result says so IN the claim, so
  that anything reading artifacts later — the audit, the answer rendering, a
  human — sees the tolerance rather than having to know to look for it."
  [claim tol exact?]
  (if exact? claim (str claim " (numerically, within " tol ")")))

(defmethod run-tool "verify_octave" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :claim :expr)]
    (fail branch m)
    (try
      (let [[s branch] (octave-session! ctx)
            claim (arg ctx :claim)
            tol (or (arg ctx :tol) 0)
            r (octave/check s (arg ctx :expr) tol)]
        (cond
          (not (:ok r))
          (fail branch (str "The expression did not evaluate to a verdict: " (:error r)
                            "\nThat is an encoding problem, not evidence about the claim.")
                :failure {:claim claim :reason (:error r)})

          (:verdict r)
          (let [exact? (:exact r)]
            {:branch branch :category :success :progress? true
             :result (str "Octave evaluated it to true. Claim CONFIRMED"
                          (if exact?
                            " by exact arithmetic."
                            (str " numerically, within " tol "."))
                          (when-not exact?
                            (str "\n\nThis is evidence about a computation, not a proof about"
                                 " the reals: it holds for these inputs at this precision."
                                 " A claim about ALL reals needs Z3 or Lean.")))
             :artifact {:kind :octave
                        :claim (octave-claim-text claim tol exact?)
                        :code (arg ctx :expr)
                        :claim-status :confirmed :tier :fast}})

          :else
          (fail branch (str "Octave evaluated it to FALSE, so the claim is not supported.")
                :failure {:claim claim :reason "the Octave check evaluated to false"}
                :artifact {:kind :octave
                           :claim (octave-claim-text claim tol (:exact r))
                           :code (arg ctx :expr)
                           :claim-status :refuted :tier :fast})))
      (catch Throwable e
        (fail branch (str "Octave is unavailable: " (ex-message e)))))))
