;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent-test
  "Phase 3: the control layer, offline.

  These assertions hold at n=1, which is the point. PR 739 measured a roughly
  2x run-to-run noise floor on identical configurations, so any claim of the
  form \"this reduces turns\" is unmeasurable at an affordable sample size,
  while \"the mechanism fired when it should and stayed silent otherwise\" is
  checkable deterministically."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is are]]
            [veriframe.agent.arbiter :as arbiter]
            [veriframe.agent.beam :as beam]
            [veriframe.agent.claims :as claims]
            [veriframe.agent.consensus :as consensus]
            [veriframe.agent.critic :as critic]
            [veriframe.engine.prolog :as prolog]
            [veriframe.agent.gates :as gates]
            [veriframe.agent.loop :as aloop]
            [veriframe.agent.resume :as resume]
            [veriframe.agent.state :as state]
            [veriframe.agent.tools :as tools]
            [veriframe.agent.verdict :as verdict]
            [veriframe.engine.lean-pool :as lean-pool]
            [veriframe.engine.lean-search :as lean-search]
            [veriframe.engine.lean-repl :as lean-repl]
            [veriframe.engine.octave :as octave]
            [veriframe.engine.smt :as smt]
            [veriframe.llm.client :as llm]
            [clojure.data.json :as json]
            [veriframe.store.artifacts :as artifacts]
            [veriframe.store.db :as db]
            [veriframe.store.failures :as failures]
            [veriframe.store.interventions :as interventions]
            [veriframe.store.journal :as journal]
            [veriframe.store.runs :as runs]))

;; --- verdict ----------------------------------------------------------------

(deftest verdict-answer-set-is-validated
  (testing "overlapping tokens are rejected at construction"
    ;; The exact bug: COMPLETE is a substring of INCOMPLETE, MET of UNMET.
    ;; dirge's parser got 11 of 27 real judge phrasings wrong, every one in the
    ;; same direction. This makes that shape impossible to define.
    (is (thrown? Throwable
                 (#'verdict/validate-answer-set!
                  {:done #{"COMPLETE"} :not-done #{"INCOMPLETE"}})))
    (is (thrown? Throwable
                 (#'verdict/validate-answer-set! {:yes #{"MET"} :no #{"UNMET"}})))
    (is (some? (#'verdict/validate-answer-set! {:pass #{"PASS"} :fail #{"FAIL"}})))))

(deftest verdict-parsing
  (are [expected text] (= expected (:verdict (verdict/parse text)))
    :pass "Looks right.\nVERDICT: PASS"
    :fail "This is NOT COMPLETE.\nVERDICT: FAIL"
    :pass "PASS"
    :fail "FAIL"
    ;; A judge writing prose says "fail" constantly in passing.
    :pass "This would fail if the encoding differed.\nVERDICT: PASS"
    :unparseable "It seems fine to me."
    :unparseable "")

  (testing "the reasoning stream is not the answer"
    ;; A reasoning judge restates the question, including the instruction's own
    ;; `VERDICT: PASS or VERDICT: FAIL`, inside <think>. Scanning the whole
    ;; response found both answers every time, and on the first live run EVERY
    ;; review came back ambiguous and the branch could never ship.
    (is (= :pass (:verdict (verdict/parse
                            (str "<think>The prompt says answer VERDICT: PASS or"
                                 " VERDICT: FAIL. I think this holds.</think>\n"
                                 "VERDICT: PASS")))))
    (is (= :fail (:verdict (verdict/parse
                            "<think>maybe VERDICT: PASS</think>\nNo.\nVERDICT: FAIL")))))

  (testing "a judge truncated mid-thought has not answered"
    (let [r (verdict/parse "<think>weighing pass against fail and I ran out of")]
      (is (= :unparseable (:verdict r)))
      (is (str/includes? (:reason r) "token cap"))))

  (testing "competing markers in the answer resolve to the last, and say so"
    (let [r (verdict/parse "VERDICT: PASS\nOn reflection.\nVERDICT: FAIL")]
      (is (= :fail (:verdict r)))
      (is (= 1 (:drafts r)))))

  (testing "anything but a clean pass fails closed"
    (are [text] (not (verdict/passed? (verdict/parse text)))
      "VERDICT: FAIL" "no verdict here" "" "<think>only thinking</think>")))

(deftest verdict-gaps
  (testing "GAP lines are collected without disturbing the verdict"
    (let [r (verdict/parse "GAP: the witness leaves X unbound\nVERDICT: PASS")]
      (is (= :pass (:verdict r)))
      (is (= ["the witness leaves X unbound"] (:gaps r)))
      (is (= :pass-with-gaps (:disagreement r)))))

  (testing "matching is case-insensitive and takes every GAP line"
    (let [r (verdict/parse "gap: first\nGAP: second\nVERDICT: PASS")]
      (is (= ["first" "second"] (:gaps r)))))

  (testing "a pass beside listed gaps is a disagreement the verdict wins"
    ;; UCLA scorer_v4 converged on the same fail-closed enum and added this
    ;; check: PASS alongside a blocking GAP is self-contradictory. PASS stands,
    ;; and the parse says it happened rather than flipping.
    (let [r (verdict/parse "GAP: x\nGAP: y\nVERDICT: PASS")]
      (is (= :pass (:verdict r)))
      (is (= :pass-with-gaps (:disagreement r)))))

  (testing "FAIL with GAP lines is consistent"
    (let [r (verdict/parse "GAP: the witness leaves X unbound\nVERDICT: FAIL")]
      (is (= :fail (:verdict r)))
      (is (nil? (:disagreement r)))))

  (testing "FAIL beside an explicit no-gaps declaration is the other disagreement"
    (let [r (verdict/parse "GAPS: none\nVERDICT: FAIL")]
      (is (= :fail (:verdict r)))
      (is (true? (:gaps-declared r)))
      (is (= [] (:gaps r)))
      (is (= :fail-without-gaps (:disagreement r))))
    (is (= :fail-without-gaps
           (:disagreement (verdict/parse "gap: none\nVERDICT: FAIL")))))

  (testing "a clean pass with no gap section records no disagreement"
    (let [r (verdict/parse "VERDICT: PASS")]
      (is (= :pass (:verdict r)))
      (is (false? (:gaps-declared r)))
      (is (nil? (:gaps r)))
      (is (nil? (:disagreement r)))))

  (testing "GAP lines inside <think> are reasoning, not declarations"
    (let [r (verdict/parse "<think>GAP: maybe a problem</think>\nVERDICT: PASS")]
      (is (= :pass (:verdict r)))
      (is (false? (:gaps-declared r)))
      (is (nil? (:gaps r))))))

(deftest verdict-minors
  (testing "a MINOR line with a FIX parses into :minors, not :gaps"
    (let [r (verdict/parse "MINOR: X is unbound FIX: bind X\nVERDICT: FAIL")]
      (is (= [{:description "X is unbound" :patch "bind X"}] (:minors r)))
      (is (nil? (:gaps r)))))
  (testing "a MINOR line without a FIX is not minor — it blocks"
    (let [r (verdict/parse "MINOR: X is unbound\nVERDICT: FAIL")]
      (is (nil? (:minors r)))
      (is (= ["X is unbound"] (:gaps r)))))
  (testing "PASS alongside minors-with-fixes is consistent"
    (let [r (verdict/parse "MINOR: typo FIX: fix it\nVERDICT: PASS")]
      (is (= :pass (:verdict r)))
      (is (nil? (:disagreement r)))))
  (testing "FAIL with only patchable minors is the third disagreement direction"
    (let [r (verdict/parse "minor: typo fix: fix it\nVERDICT: FAIL")]
      (is (= :fail (:verdict r)))
      (is (= :fail-with-only-patchables (:disagreement r)))))
  (testing "a gap blocks even alongside a carried patch"
    (let [r (verdict/parse "GAP: the witness leaves X unbound\nMINOR: typo FIX: fix it\nVERDICT: FAIL")]
      (is (= :fail (:verdict r)))
      (is (= ["the witness leaves X unbound"] (:gaps r)))
      (is (= [{:description "typo" :patch "fix it"}] (:minors r)))
      (is (nil? (:disagreement r)))))
  (testing "MINOR lines inside <think> are reasoning, not declarations"
    (let [r (verdict/parse "<think>MINOR: maybe a problem FIX: fix it</think>\nVERDICT: PASS")]
      (is (= :pass (:verdict r)))
      (is (nil? (:minors r)))
      (is (nil? (:gaps r)))
      (is (nil? (:disagreement r))))))

(deftest verdict-gap-disagreement-is-journalled
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    ;; Both judge tools journal the disagreement, and both directions of it
    ;; (PASS beside listed gaps, FAIL beside GAPS: none), so whichever handler
    ;; is edited next has its :data shape pinned.
    (let [calls (atom 0)]
      (with-redefs [llm/chat (fn [& _]
                               (case (swap! calls inc)
                                 1 {:content "GAP: the witness leaves X unbound\nVERDICT: PASS"}
                                 {:content "GAPS: none\nVERDICT: FAIL"}))]
        (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                         :tool-name "audit"
                         :args {:claim "every element of S satisfies P" :proposedAnswer "42"}})
        (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                         :tool-name "review"
                         :args {:claim "every element of S satisfies P"
                                :rationale "independent because encoded in Z3 instead of Prolog"}}))
      (let [evs (filter #(= "verdict-gap-disagreement" (:kind %))
                        (journal/events-since c rid 0))
            audit-ev (some #(when (str/includes? (:data %) "pass-with-gaps") %) evs)
            review-ev (some #(when (str/includes? (:data %) "fail-without-gaps") %) evs)]
        (is (= 2 (count evs)) "one disagreement event per judge call")
        (is (some? audit-ev) "PASS beside gaps is journalled from audit")
        (is (some? review-ev) "FAIL beside GAPS: none is journalled from review")
        (is (str/includes? (:data audit-ev) "\"audit\""))
        (is (str/includes? (:data review-ev) "\"review\""))))))

(deftest audit-failure-carries-patches
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    ;; A judge that fails while listing patchable defects must hand the branch
    ;; the repairs verbatim — the next turn is a fresh model call and the
    ;; patches are the whole point of the refusal.
    (with-redefs [llm/chat (fn [& _]
                             {:content (str "MINOR: the answer leaves X unbound"
                                            " FIX: bind X to a witness\n"
                                            "VERDICT: FAIL")})]
      (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                               :tool-name "audit"
                               :args {:claim "every element of S satisfies P" :proposedAnswer "42"}})]
        (is (str/includes? (:result r) "PATCHES:"))
        (is (str/includes? (:result r) "bind X to a witness"))
        (is (str/includes? (get-in r [:failure :reason]) "1 patchable"))))))

(deftest judges-receive-the-exemption-list
  ;; The DO-NOT-FLAG list only works if the judge actually sees it, and the
  ;; never-exempt section is what keeps it from reading as a relaxation. Both
  ;; must reach both judges or the exemption is a file, not a mechanism.
  (let [b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})
        prompts (atom [])]
    (with-redefs [llm/chat (fn [_ _ msgs _]
                             (swap! prompts conj
                                    (:content (last msgs)))
                             {:content "VERDICT: FAIL"})]
      (tools/run-tool {:branch b :turn 1 :tool-name "audit"
                       :args {:claim "every element of S satisfies P" :proposedAnswer "42"}})
      (tools/run-tool {:branch b :turn 1 :tool-name "review"
                       :args {:claim "every element of S satisfies P" :rationale "different encoding"}}))
    (is (= 2 (count @prompts)))
    (doseq [p @prompts]
      (is (str/includes? p "what is not a gap")
          "the exemption list reaches the judge")
      (is (str/includes? p "Never exempt")
          "and so does the section that keeps it from being a relaxation")
      (is (str/includes? p "universal claim verified only at instances")))))

(deftest prompt-digest-covers-the-exemption-list
  ;; Prompts are files so a pass-rate change localizes to one file; that only
  ;; holds if every judge-facing file participates in the digest.
  (is (not= (aloop/prompt-digest)
            (with-redefs [aloop/judge-exemptions (fn [] "changed")]
              (aloop/prompt-digest)))))

;; --- gates and the arbiter --------------------------------------------------

(defn- branch-with [& {:as overrides}]
  (merge (state/new-branch {:id "B1" :problem "p"}) overrides))

(deftest exactly-one-steer-per-boundary
  (testing "several gates can hold at once and exactly one is emitted"
    (let [b (branch-with :consecutive-failures 3
                         :any-progress? true
                         :turns-since-progress 9
                         :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed :turn 1}]
                         :turns (vec (repeat 5 {})))
          ctx {:branch b :max-turns 40}
          d (arbiter/decide ctx)]
      (is (>= (count (arbiter/eligible ctx)) 3)
          "the scenario has to actually have competing gates or it proves nothing")
      (is (some? d))
      (is (= :emergency-review (:gate d)) "the highest-priority eligible gate wins")
      (is (seq (:passed-over d)) "what was outranked is recorded, not discarded")
      (is (string? (:message d)))
      (is (string? (:prediction d)) "every gate declares what it expects next")))

  (testing "a human directive outranks every machine gate"
    ;; dirge PR 717 as a design property rather than a bug fix.
    (let [b (branch-with :consecutive-failures 5
                         :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed :turn 1}])
          d (arbiter/decide {:branch b :max-turns 40
                             :directive {:payload "stop and ship what you have"}})]
      (is (= :human-directive (:gate d)))
      (is (str/includes? (:message d) "stop and ship what you have"))))

  (testing "a fresh branch is not nudged"
    (is (nil? (arbiter/decide {:branch (branch-with) :max-turns 40})))))

(deftest gates-stay-silent-when-they-should
  (testing "the stall gate arms only after the branch has made progress"
    ;; Exploration is never nudged; the prologue cap covers the other case.
    (let [never-progressed (branch-with :any-progress? false :turns-since-progress 20)]
      (is (not-any? #{:progress-stalled} (map :gate (arbiter/eligible
                                                     {:branch never-progressed
                                                      :max-turns 40}))))))

  (testing "the prologue cap covers the branch that produced nothing at all"
    ;; The case every other guard is principled-blind to: no failures for the
    ;; stuck gate, no progress event to arm the stall gate.
    (let [b (branch-with :any-progress? false :turns (vec (repeat 8 {})))]
      (is (= :prologue-cap (:gate (arbiter/decide {:branch b :max-turns 40}))))))

  (testing "the tier escalation fires only while everything is fast-tier"
    (let [fast-only (branch-with :artifacts (vec (repeat 3 {:claim-status :confirmed}))
                                 :tiers-seen #{:fast})
          has-slow (assoc fast-only :tiers-seen #{:fast :slow})]
      (is (some #{:tier-escalation} (map :gate (arbiter/eligible {:branch fast-only
                                                                  :max-turns 40}))))
      (is (not-any? #{:tier-escalation} (map :gate (arbiter/eligible {:branch has-slow
                                                                      :max-turns 40}))))))

  (testing "a spent budget stops a gate re-firing"
    (let [b (branch-with :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed :turn 1}]
                         :gate-history [{:gate :milestone :turn 1}])]
      (is (not-any? #{:milestone} (map :gate (arbiter/eligible {:branch b
                                                                :max-turns 40}))))))

  (testing "the emergency review is guarded like every other steer"
    ;; A live knights-3 run re-fired it on three consecutive boundaries, all
    ;; predictions unmet: its precondition persists while the branch is busy
    ;; complying. It was the one steer gate with no re-fire guard.
    (let [b (branch-with :consecutive-failures 3
                         :turns (vec (repeat 4 {}))
                         :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed :turn 3}])]
      (is (some #{:emergency-review} (map :gate (arbiter/eligible {:branch b
                                                                   :max-turns 40})))
          "fires while at the cull threshold holding a recent confirmation")
      (let [spent (assoc b :gate-history [{:gate :emergency-review :turn 4}])]
        (is (not-any? #{:emergency-review}
                      (map :gate (arbiter/eligible {:branch spent :max-turns 40})))
            "and once is all it gets")))))

(deftest wind-down-steers-the-branch-to-ship
  (testing "fires at and past the wind-down fraction of the turn cap"
    (let [ctx-at (fn [turns]
                   {:branch (branch-with :turns (vec (repeat turns {})))
                    :max-turns 40})]
      (is (not-any? #{:wind-down} (map :gate (arbiter/eligible (ctx-at 33))))
          "silent below the fraction")
      (let [d (arbiter/decide (ctx-at 34))] ; 34/40 = 0.85
        (is (= :wind-down (:gate d)) "fires exactly at the fraction")
        (is (string? (:message d)))
        (is (str/includes? (:message d) "at turn 34 of 40")
            "the steer says where the branch stands")
        (is (string? (:prediction d)) "every gate declares what it expects next")
        (is (some #{:turn-budget :prologue-cap} (:passed-over d))
            "the ship steer outranks the plain notices")))
    (testing "fires once per branch; a spent re-fire guard keeps it silent"
      (let [b (branch-with :turns (vec (repeat 36 {}))
                           :gate-history [{:gate :wind-down :turn 34}])]
        (is (not-any? #{:wind-down}
                      (map :gate (arbiter/eligible {:branch b :max-turns 40})))))))

  (testing "silent on a branch that already shipped"
    (let [b (branch-with :turns (vec (repeat 36 {}))
                         :status :done :final-answer "x")]
      (is (not-any? #{:wind-down}
                    (map :gate (arbiter/eligible {:branch b :max-turns 40}))))))

  (testing "done-blocked outranks it when both hold"
    (let [b (branch-with :turns (vec (repeat 36 {}))
                         :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed :turn 1}])
          d (arbiter/decide {:branch b :max-turns 40
                             :done-block "`done` refused.\n\nNo confirmed artifact."})]
      (is (= :done-blocked (:gate d)) "the correctness rung wins, not the budget steer")
      (is (some #{:wind-down} (:passed-over d))))))

(deftest gate-config-is-coherent
  (testing "every gate with a budget names a threshold that exists"
    (doseq [g gates/gates :when (:budget g)]
      (is (some? (gates/threshold (:budget g)))
          (str (:gate g) " names a budget with no entry in gates.edn"))))

  (testing "priorities are unique, or the arbiter's choice is arbitrary"
    (let [ps (map :priority gates/gates)]
      (is (= (count ps) (count (distinct ps))))))

  (testing "the capability tier may not tune a verification or progress guard"
    ;; PR 740's rule: a signal may only tune a guard that fires on the same
    ;; thing the signal measures. The tier observes tool-call mechanics only.
    (doseq [k [:cull-threshold :stuck-threshold :progress-stall-threshold
               :prologue-cap :fast-verify-edit-threshold]]
      (is (false? (get-in (gates/config) [k :capability-tunable?]))
          (str k " must not be capability-tunable"))))

  (testing "no cost ceiling is capability-tunable"
    ;; Scaling one up for a struggling run means spending more on the run
    ;; already in trouble.
    (doseq [[k v] (gates/config) :when (= :cost-ceiling (:kind v))]
      (is (false? (:capability-tunable? v)) (str k " is a cost ceiling")))))

;; --- prediction settling ----------------------------------------------------

(deftest predictions-settle-deterministically
  (let [firing {:gate :milestone :turn 5 :window 2}
        b (branch-with)]
    (testing "met when the branch does what the gate asked"
      (is (= :met (arbiter/settle firing {:current-turn 6 :tools-called ["review"]
                                          :branch-before b :branch-after b}))))
    (testing "still open inside the window"
      (is (nil? (arbiter/settle firing {:current-turn 6 :tools-called ["add_rule"]
                                        :branch-before b :branch-after b}))))
    (testing "unmet once the window passes"
      (is (= :unmet (arbiter/settle firing {:current-turn 7 :tools-called ["add_rule"]
                                            :branch-before b :branch-after b})))))

  (testing "progress gates settle on artifacts, not on tool names"
    (let [before (branch-with)
          after (branch-with :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed}])]
      (is (= :met (arbiter/settle {:gate :progress-stalled :turn 3 :window 3}
                                  {:current-turn 4 :tools-called ["verify"]
                                   :branch-before before :branch-after after})))))

  (testing "wind-down settles on the ship tools"
    (let [firing {:gate :wind-down :turn 34 :window 3}
          b (branch-with)]
      (is (= :met (arbiter/settle firing {:current-turn 35 :tools-called ["review"]
                                          :branch-before b :branch-after b})))
      (is (= :met (arbiter/settle firing {:current-turn 36 :tools-called ["audit"]
                                          :branch-before b :branch-after b})))
      (is (= :met (arbiter/settle firing {:current-turn 35 :tools-called ["done"]
                                          :branch-before b :branch-after b})))
      (is (nil? (arbiter/settle firing {:current-turn 35 :tools-called ["verify_claim"]
                                        :branch-before b :branch-after b}))
          "still open inside the window")
      (is (= :unmet (arbiter/settle firing {:current-turn 37 :tools-called ["verify_claim"]
                                            :branch-before b :branch-after b}))
          "unmet once the window passes"))))

(deftest every-gate-that-fires-can-actually-be-met
  ;; vf-9bo. settle dispatches on gate in a case whose fallthrough is false, so
  ;; a gate missing from that case is not merely unhandled — its prediction can
  ;; never come true. It fires, expires, and is recorded :unmet, which reads
  ;; off the gate table as a gate the model ignores when the harness never
  ;; checked. safe-state sat at 0 met / 2 unmet across gen-22/23/24 for exactly
  ;; this reason.
  ;;
  ;; The same shape as the failures charged to the cull counter in vf-jki: a
  ;; tally blaming the branch for something nobody measured. This asserts the
  ;; dispatch covers the table, so adding a gate without a settle rule fails
  ;; here rather than silently three generations later.
  (let [b (branch-with)
        every-tool (vec (tools/tool-names))]
    (doseq [{:keys [gate]} gates/gates]
      (is (= :met (arbiter/settle {:gate gate :turn 1 :window 3}
                                  {:current-turn 2
                                   :tools-called every-tool
                                   :branch-before b
                                   :branch-after (branch-with
                                                  :artifacts
                                                  [{:claim "every element of S satisfies P"
                                                    :claim-status :confirmed}])}))
          (str "gate " gate " has no way to be met; it can only expire unmet")))))

(deftest safe-state-settles-on-the-fallback-it-names
  ;; Its prediction is "the branch retracts, changes technique, or ships what
  ;; it has", and each of those is a tool call the harness can see.
  (let [b (branch-with)
        settles (fn [tool] (arbiter/settle {:gate :safe-state :turn 1 :window 3}
                                           {:current-turn 2 :tools-called [tool]
                                            :branch-before b :branch-after b}))]
    (are [tool] (= :met (settles tool))
      "retract_rule" "thesis" "done" "give_up" "review")
    (is (nil? (settles "verify_smt")) "still open inside the window")))

;; --- the done gate ----------------------------------------------------------

(deftest answer-must-be-covered-by-evidence
  (testing "a number in the answer that appears in no artifact is uncovered"
    ;; The deterministic claim-evidence gate. An answer asserting something no
    ;; artifact mentions is a fabricated verification report (dirge PR 749).
    (let [artifacts [{:claim "the set has size 23" :code "(assert (= n 23))" :witness nil}]]
      (is (empty? (tools/uncovered-tokens "the answer is 23" artifacts)))
      (is (= ["24"] (tools/uncovered-tokens "the answer is 24" artifacts)))))

  (testing "stopwords and short words are not evidence claims"
    (is (empty? (tools/uncovered-tokens "the answer is that it exists"
                                        [{:claim "every element of S satisfies P" :code "" :witness nil}]))))

  (testing "grammar that slipped through the inflections is not an assertion"
    ;; A live refusal listed `does`, `follow`, `from` and `having` beside the
    ;; genuine catches, telling the branch to verify or remove the word "from".
    ;; `follows`, `have` and `has` were already stopwords; their other forms
    ;; were not, and `from` and `does` were missing outright. All four are
    ;; framing by the list's own test — none can carry a specific claim — so
    ;; adding them costs the gate nothing.
    (let [artifacts [{:claim "the minimum is 2" :code "" :witness nil}]]
      (is (empty? (tools/uncovered-tokens
                   "this does follow from having the minimum 2" artifacts)))
      (is (= ["residue"] (tools/uncovered-tokens
                          "this does follow from having a residue" artifacts))
          "and the substantive term is still caught")))

  (testing "the witness counts as evidence, not only the claim text"
    (is (empty? (tools/uncovered-tokens "a is knave"
                                        [{:claim "solved" :code ""
                                          :witness [{:A "knave"}]}])))))

(deftest coverage-does-not-refuse-the-words-an-honest-partial-answer-needs
  ;; vf-w2k. B4 of run 0d0c3560 called `done` eight times over twenty turns
  ;; with a PASSING audit, and was refused six times for asserting `stated`,
  ;; `facts`, `against`, `together`, `asked`, `settled`, `establish`,
  ;; `evidence`, `found`, `showed`. None of those is an assertion.
  ;;
  ;; Worse, the relevance rung added the same morning TELLS a branch to state
  ;; which questions it did not settle — and the thing you failed to establish
  ;; is, by construction, absent from your evidence. The two rungs could not
  ;; both be satisfied.
  (let [artifacts [{:claim "enumerating all 2^12 assignments gives 8 flows of cost 6"
                    :code "" :witness nil}
                   {:claim "the residue at the top-left square is +1" :code "" :witness nil}]]

    (testing "framing vocabulary is not an assertion"
      (is (empty? (tools/uncovered-tokens
                   (str "Taken together, what the evidence establishes is 8 flows."
                        " The general question was asked but remains unsettled.")
                   artifacts))))

    (testing "the problem's own vocabulary is never a fabrication"
      ;; The rung exists to catch an answer asserting a number or a name that
      ;; appears nowhere in the evidence — a fabricated verification report.
      ;; A term the HARNESS put in front of the model cannot be fabricated by
      ;; it. B4 of run 0d0c3560 was refused for `polynomial-time`,
      ;; `canonical-selection-rule` and `recovery-guarantee`, every one of them
      ;; lifted from the problem it had been asked to solve, and every one
      ;; unavoidable in saying which parts of that problem it had not reached.
      (let [problem (str "Is there a canonical selection rule, computable in"
                         " polynomial time? No recovery guarantee exists for"
                         " 2D unwrapping in any form.")
            flagged (set (tools/uncovered-tokens
                          (str "No canonical-selection-rule is given and"
                               " polynomial-time computability and the"
                               " recovery-guarantee are not reached.")
                          artifacts problem))]
        (is (empty? flagged) (str "still flagged: " (pr-str flagged))))
      (testing "a hyphenated compound of framing words is still framing"
        ;; `lean-verified` and `engine-confirmed` are single tokens, so both
        ;; halves being stopwords did not save them. They are provenance —
        ;; the one thing an artifact can never mention, since an artifact is
        ;; about the problem and says nothing about the engine that ran it.
        (let [flagged (set (tools/uncovered-tokens
                            "what is lean-verified and engine-confirmed is stated above"
                            artifacts))]
          (is (empty? flagged) (str "still flagged: " (pr-str flagged))))
        (is ((set (tools/uncovered-tokens "the optimal-flow set is large" artifacts))
             "optimal-flow")
            "but a compound with a substantive half is not exempt"))

      (testing "but a term from outside the problem AND the evidence is caught"
        (is ((set (tools/uncovered-tokens
                   "the vortices unbind at the transition"
                   artifacts "a canonical selection rule in polynomial time"))
             "vortices"))))

    (testing "naming what you did NOT settle needs the audit's own restatement"
      ;; The hard half, and the one stopwords cannot reach. `polynomial-time`
      ;; is a substantive term and it is absent from the evidence for the only
      ;; reason that matters: nobody established it. The relevance rung asks
      ;; the branch to say so. The audit — which has passed, and whose job was
      ;; to state what the evidence does and does not cover — is where that
      ;; sentence is licensed from.
      (let [answer "polynomial-time computability is not settled here"]
        (is (seq (tools/uncovered-tokens answer artifacts))
            "with nothing but artifacts, the honest sentence is refused")
        (is (empty? (tools/uncovered-tokens
                     answer artifacts
                     (str "8 flows of cost 6 on that graph; polynomial-time"
                          " computability of the tie-break is not established")))
            "and the passing audit's ESTABLISHED line licenses it")))

    (testing "an inflection of a word in the evidence is covered"
      ;; str/includes? on the raw haystack sees none of these.
      (let [flagged (set (tools/uncovered-tokens
                          "the residues and the enumeration give 8 flows"
                          artifacts))]
        (is (not (flagged "residues")) "evidence says `residue`")
        (is (not (flagged "enumeration")) "evidence says `enumerating`")))

    (testing "a hyphen is not a different word"
      (is (not ((set (tools/uncovered-tokens "the top-left residue is +1" artifacts))
                "top-left"))))

    (testing "a number in no artifact is still refused — that is what this is for"
      (is (= ["9"] (tools/uncovered-tokens "there are 9 flows" artifacts)))
      (is ((set (tools/uncovered-tokens "the threshold is 0.31" artifacts)) "0.31")))

    (testing "a substantive word in no artifact is still refused"
      (is ((set (tools/uncovered-tokens "a vortex is present" artifacts)) "vortex")))

    (testing "the passing audit's own restatement covers WORDS but never numbers"
      ;; The audit is a gate that already passed on the merits and its
      ;; ESTABLISHED line is the harness's account of what the evidence shows,
      ;; so an answer may echo its prose. It may not inherit its arithmetic:
      ;; a number still has to come from an artifact, which is the whole
      ;; reason this rung exists.
      (let [established (str "the lexicographic tie-break selects a unique flow"
                             " whenever the optimal set is finite, and 42 of them exist")]
        (is (empty? (tools/uncovered-tokens
                     "the lexicographic tie-break selects a unique flow"
                     artifacts established)))
        (is (= ["42"] (tools/uncovered-tokens
                       "there are 42 flows" artifacts established))
            "a number the audit restated but no engine confirmed is not covered")))))

(deftest numbers-block-the-ship-and-words-only-advise
  ;; vf-kpn. Three rounds of widening the stopword list got B4's refusals from
  ;; eight words down to one — `formed` — and it still could not ship. Eleven
  ;; done attempts across two branches, roughly forty turns, every audit
  ;; passing. Ordinary English does not run out.
  ;;
  ;; The rung's own docstring says numbers are the point, and the number check
  ;; has never produced a false positive. The word check is a lexical proxy for
  ;; "does this assert something unsupported", which the audit answers with
  ;; semantics and in context. So numbers refuse; words go to the audit and the
  ;; journal, and never strand an audited answer.
  (let [artifacts [{:claim "the graph has 8 optimal flows of cost 6"
                    :claim-status :confirmed :kind :smt :tier :slow :code ""}]
        ship (fn [answer]
               (tools/run-tool
                {:branch (branch-with :problem "How many optimal flows are there?"
                                      :artifacts artifacts
                                      :last-review {:passed true}
                                      :last-audit {:passed true :proposed-answer answer
                                                   :established answer :relaxation? false})
                 :turn 1 :tool-name "done" :args {:answer answer}}))]

    (testing "a number in no artifact still refuses"
      (let [r (ship "the graph has 9 optimal flows")]
        (is (= :failure (:category r)))
        (is (str/includes? (:result r) "9"))))

    (testing "an English word in no artifact does not"
      (with-redefs [llm/chat (fn [& _] {:content "ASKS: a count\nSUPPLIES: a count\nVERDICT: PASS"})]
        (let [r (ship "the graph formed by the gadgets has 8 optimal flows of cost 6")]
          (is (= :success (:category r))
              (str "refused over a word: " (:result r)))
          (is (= :done (:status (:branch r)))))))

    (testing "and the audit is handed those words to weigh in context"
      (let [prompts (atom [])]
        (with-redefs [llm/chat (fn [_ _ msgs _]
                                 (swap! prompts conj (:content (second msgs)))
                                 {:content "GAPS: none\nESTABLISHED: x\nRELAXATION: no\nVERDICT: PASS"})]
          (tools/run-tool
           {:branch (branch-with :thesis {:goal "count them" :subClaims []}
                                 :artifacts artifacts)
            :turn 1 :tool-name "audit"
            :args {:claim "every element of S satisfies P" :proposedAnswer "the vortex density is 8"}}))
        (is (re-find #"(?i)vortex" (str (first @prompts)))
            "the audit sees the word the lexical check flagged")
        (is (re-find #"(?i)appear in no artifact|no artifact" (str (first @prompts)))
            "and is told what that means")))))

(deftest verdict-parses-established-and-relaxation
  (testing "ESTABLISHED and RELAXATION lines are read"
    (let [j (verdict/parse (str "The artifacts cover the cases I checked.\n"
                                "ESTABLISHED: the sequence converges for n = 1, 2, 3\n"
                                "RELAXATION: yes\n"
                                "VERDICT: PASS"))]
      (is (= "the sequence converges for n = 1, 2, 3" (:established j)))
      (is (= true (:relaxation? j)))))

  (testing "RELAXATION: no reads as false"
    (is (= false (:relaxation? (verdict/parse "RELAXATION: no\nVERDICT: PASS")))))

  (testing "absent lines stay absent — back-compat"
    (let [j (verdict/parse "VERDICT: PASS")]
      (is (nil? (:established j)))
      (is (nil? (:relaxation? j)))))

  (testing "several ESTABLISHED lines: the last wins, like verdict markers"
    (let [j (verdict/parse (str "ESTABLISHED: draft one\n"
                                "ESTABLISHED: draft two\n"
                                "VERDICT: PASS"))]
      (is (= "draft two" (:established j)))))

  (testing "inside <think> is a weighing, not a commitment"
    (let [j (verdict/parse (str "<think>ESTABLISHED: nope\nRELAXATION: no</think>\n"
                                "VERDICT: PASS"))]
      (is (nil? (:established j)))
      (is (nil? (:relaxation? j)))))

  (testing "an ESTABLISHED line is not a gap declaration"
    (let [j (verdict/parse "ESTABLISHED: x holds\nVERDICT: PASS")]
      (is (false? (:gaps-declared j)))
      (is (nil? (:gaps j))))))

(deftest audit-stores-established-and-relaxation
  (let [b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})]
    (with-redefs [llm/chat (fn [& _]
                             {:content (str "ESTABLISHED: the sequence converges for n = 1, 2, 3\n"
                                            "RELAXATION: yes\n"
                                            "VERDICT: PASS")})]
      (let [la (:last-audit (:branch (tools/run-tool
                                      {:branch b :turn 1 :tool-name "audit"
                                       :args {:claim "every element of S satisfies P" :proposedAnswer "42"}})))]
        (is (true? (:passed la)))
        (is (= "the sequence converges for n = 1, 2, 3" (:established la)))
        (is (true? (:relaxation? la)))))))

(deftest judge-retries-a-token-capped-verdict
  ;; vf-42e: in the magic-square live run three audits came back "unparseable
  ;; — the judge produced only reasoning and no answer", each costing the
  ;; branch a turn; the branch reached an audit-approved answer exactly at the
  ;; turn cap and could not ship. A well-formed response with no verdict is a
  ;; judge process failure, not evidence about the claim, so it is retried
  ;; inside the same tool call — sharper instruction, doubled token budget —
  ;; and the retry is journaled so runs stay measurable.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})
        calls (atom [])]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [llm/chat (fn [_ _ msgs opts]
                             (swap! calls conj {:msgs msgs :opts opts})
                             (if (= 1 (count @calls))
                               {:content "<think>reasoning that never commits"}
                               {:content "GAPS: none\nVERDICT: PASS"}))]
      (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                               :llm-config {:max-tokens 100}
                               :tool-name "audit"
                               :args {:claim "every element of S satisfies P" :proposedAnswer "42"}})]
        (is (true? (get-in r [:branch :last-audit :passed]))
            "the retried verdict is the one that counts")
        (is (= 2 (count @calls)) "one retry resolved it")
        (is (str/includes? (get-in (second @calls) [:msgs 0 :content])
                           "previous response")
            "the retry names what went wrong last time")
        (is (= 200 (get-in (second @calls) [:opts :max-tokens]))
            "the retry doubles the token budget")
        (is (= 1 (count (filter #(= "judge-retry" (:kind %))
                                (journal/events-since c rid 0))))
            "the retry is journaled")))))

(deftest judge-retries-are-bounded
  (let [b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})
        calls (atom 0)]
    (with-redefs [llm/chat (fn [& _] (swap! calls inc)
                             {:content "<think>never a verdict"})]
      (let [r (tools/run-tool {:branch b :turn 1 :tool-name "audit"
                               :args {:claim "every element of S satisfies P" :proposedAnswer "42"}})]
        (is (= :failure (:category r)) "exhausted retries fail closed")
        (is (= 3 @calls) "attempts are bounded")))))

(deftest judge-transport-failure-is-not-retried
  ;; The retry exists for a judge that ANSWERED without a verdict. A transport
  ;; failure already went through llm/chat's own bounded retry loop, and a
  ;; second loop here would multiply that by three.
  (let [b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})
        calls (atom 0)]
    (with-redefs [llm/chat (fn [& _] (swap! calls inc)
                             (throw (ex-info "socket reset" {})))]
      (let [r (tools/run-tool {:branch b :turn 1 :tool-name "audit"
                               :args {:claim "every element of S satisfies P" :proposedAnswer "42"}})]
        (is (= :failure (:category r)))
        (is (= 1 @calls))))))

(deftest judge-budget-is-sized-to-the-prompt-not-retried-into
  ;; vf-mti. In gen-10 of the covering campaign the judge became the beam's
  ;; critical path: over rounds of width >= 7, audit and review were the last
  ;; branch to finish 24 times out of 28 while taking a quarter of the turns,
  ;; and verify_lean gated a round once in 47. The cause was the retry above
  ;; firing as the NORM rather than the exception — 39 of the run's 46 retries
  ;; landed after turn 40, once claims turned into exact-rational assertions
  ;; over 20-odd moduli. Measured over those 69 judge calls: the ones that ran
  ;; out of budget carried a mean of 16.8 rational literals and 2.8 with
  ;; four-or-more-digit denominators; the ones that answered first time carried
  ;; 5.2 and 0.7. So the density of exact arithmetic is the signal, and a
  ;; prompt carrying it should OPEN at the budget the retry would have reached
  ;; instead of paying for a doomed first call.
  (testing "exact arithmetic is what marks a prompt expensive"
    (is (tools/arithmetic-heavy? "E - 2*(1-D) = 83/496125 > 0")
        "one four-digit denominator is exact-rational work on its own")
    (is (tools/arithmetic-heavy?
         "E = 3/9+3/15+3/21+3/27+3/45+3/63+3/75+3/81+3/105 < 2*(1-D)")
        "a long sum of small fractions is the same work spread out"))
  (testing "ordinary prose about fractions is not"
    (is (not (tools/arithmetic-heavy?
              "no covering system is supported on the primes {3,5} alone")))
    (is (not (tools/arithmetic-heavy?
              "the density 1/2 exceeds both 1/3 and 1/5"))
        "a handful of small fractions is prose, not a certificate"))
  (testing "a heavy prompt opens where the retry would have escalated to"
    (is (= 200 (tools/judge-max-tokens {:max-tokens 100} "83/496125" 1)))
    (is (= 100 (tools/judge-max-tokens {:max-tokens 100} "a plain claim" 1))))
  (testing "a retry still escalates past wherever the first attempt opened"
    (is (= 200 (tools/judge-max-tokens {:max-tokens 100} "a plain claim" 2)))
    (is (= 400 (tools/judge-max-tokens {:max-tokens 100} "83/496125" 2))
        "starting high must not make the retry a no-op"))
  (testing "escalation is capped, so no provider ceiling is blown"
    (is (= 400 (tools/judge-max-tokens {:max-tokens 100} "83/496125" 3))))
  (testing "an unset budget stays unset — the provider default rules"
    (is (nil? (tools/judge-max-tokens {} "83/496125" 1)))))

(deftest an-arithmetic-heavy-audit-opens-at-the-doubled-budget
  ;; The end of vf-mti: the sizing has to reach the live call, and it has to
  ;; buy the verdict on the FIRST attempt rather than the second.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        heavy (str "For the P315 pool the aggregate mod-3 maxima are"
                   " D = 2754/6125 and E = 109237/99225, so in exact rational"
                   " arithmetic E - 2*(1-D) = 83/496125 > 0")
        b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim heavy :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})
        calls (atom [])]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [llm/chat (fn [_ _ msgs opts]
                             (swap! calls conj {:msgs msgs :opts opts})
                             {:content "GAPS: none\nVERDICT: PASS"})]
      (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                       :llm-config {:max-tokens 100}
                       :tool-name "audit"
                       :args {:claim heavy :proposedAnswer "42"}})
      (is (= 1 (count @calls)) "the verdict arrives without a retry")
      (is (= 200 (get-in (first @calls) [:opts :max-tokens]))
          "the first call already carries the doubled budget")
      (is (empty? (filter #(= "judge-retry" (:kind %))
                          (journal/events-since c rid 0)))
          "nothing was spent on a doomed attempt"))))

(deftest relaxation-is-journalled-as-thesis-drift
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "the sequence converges for all natural n"
                           :technique "t" :subClaims []}
                  :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    ;; A passing audit that declares the evidence weaker than the goal records
    ;; the drift. The thesis is not re-pinned to the restatement — that is the
    ;; UCLA hole — so the journal entry is the only trace.
    (with-redefs [llm/chat (fn [& _]
                             {:content (str "ESTABLISHED: only the checked cases hold\n"
                                            "RELAXATION: yes\n"
                                            "VERDICT: PASS")})]
      (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                       :tool-name "audit"
                       :args {:claim "every element of S satisfies P" :proposedAnswer "42"}}))
    (let [evs (filter #(= "thesis-drift" (:kind %))
                      (journal/events-since c rid 0))]
      (is (= 1 (count evs)) "one drift entry per relaxation audit")
      (is (str/includes? (:data (first evs))
                         "the sequence converges for all natural n"))
      (is (str/includes? (:data (first evs)) "only the checked cases hold")))))

(deftest done-refuses-a-full-claim-answer-on-a-relaxation-audit
  ;; The audit passed but declared the evidence a weakening of the thesis; the
  ;; answer still asserts the full claim. The done gate refuses, naming the
  ;; drift: what the thesis asked vs what the evidence establishes.
  (let [artifact {:claim "the sequence converges for the checked values n = 1, 2, 3"
                  :claim-status :confirmed :kind :smt :tier :slow :code "c1"}
        b (branch-with :thesis {:goal "the sequence converges for all natural n"
                                :technique "t" :subClaims []}
                       :artifacts [artifact]
                       :last-audit {:passed true
                                    :proposed-answer "the sequence converges for every natural number n, with limit 2"
                                    :established "the sequence converges for the checked values n = 1, 2, 3"
                                    :relaxation? true})]
    (let [r (tools/run-tool {:branch b :turn 1 :tool-name "done"
                             :args {:answer "the sequence converges for every natural number n, with limit 2"}})]
      (is (= :failure (:category r)))
      (is (str/includes? (:result r) "relaxation"))
      (is (str/includes? (:result r) "for all natural n")
          "the refusal names what the thesis asked")
      (is (str/includes? (:result r) "checked values n = 1, 2, 3")
          "and what the audit says the evidence establishes"))))

(deftest done-accepts-an-answer-that-states-the-established-claim
  (let [artifact {:claim "the sequence converges for the checked values n = 1, 2, 3"
                  :claim-status :confirmed :kind :smt :tier :slow :code "c1"}
        b (branch-with :thesis {:goal "the sequence converges for all natural n"
                                :technique "t" :subClaims []}
                       :artifacts [artifact]
                       :last-audit {:passed true
                                    :proposed-answer "the sequence converges for the checked values n = 1, 2, 3"
                                    :established "the sequence converges for the checked values n = 1, 2, 3"
                                    :relaxation? true})]
    (let [r (tools/run-tool {:branch b :turn 1 :tool-name "done"
                             :args {:answer "the sequence converges for the checked values n = 1, 2, 3"}})]
      (is (= :done (:status (:branch r))))
      (is (= :success (:category r))))))

(deftest done-with-no-answer-ships-the-audited-text
  ;; vf-691: two consecutive live runs produced audit-approved mathematics and
  ;; died re-typing it — the model reformats the approved answer, the verbatim
  ;; rung refuses, and the turn cap lands before a re-audit. The approved text
  ;; is already on the branch, so `done` with no answer ships it exactly.
  (let [approved "the sequence converges for the checked values n = 1, 2, 3"
        artifact {:claim "the sequence converges for the checked values n = 1, 2, 3"
                  :claim-status :confirmed :kind :smt :tier :slow :code "c1"}
        b (branch-with :thesis {:goal "the sequence converges for the checked values"
                                :technique "t" :subClaims []}
                       :artifacts [artifact]
                       :last-audit {:passed true
                                    :proposed-answer approved
                                    :established approved
                                    :relaxation? false})]
    (let [r (tools/run-tool {:branch b :turn 1 :tool-name "done" :args {}})]
      (is (= :success (:category r)))
      (is (= :done (:status (:branch r))))
      (is (= approved (:final-answer (:branch r)))
          "what ships is the audited text, character for character"))))

(deftest done-with-no-answer-needs-a-passing-audit
  ;; Shipping by reference needs a referent. No passing audit, no default.
  (let [artifact {:claim "something confirmed"
                  :claim-status :confirmed :kind :smt :tier :slow :code "c1"}]
    (doseq [audit [nil {:passed false :proposed-answer "rejected text"}]]
      (let [b (branch-with :artifacts [artifact] :last-audit audit)
            r (tools/run-tool {:branch b :turn 1 :tool-name "done" :args {}})]
        (is (= :failure (:category r)))
        (is (str/includes? (:result r) "audit")
            "the refusal points at the missing audit")))))

;; --- the answer has to be an answer to THIS problem -------------------------
;;
;; vf-eq9. The phase-unwrapping run shipped, as its answer to "when can 2D
;; phase unwrapping be done exactly, and by a polynomial-time algorithm?", a
;; true statement about four oriented edges of a 4-cycle. No phase field, no
;; noise parameter, no torus, no threshold, no algorithm.
;;
;; Both existing gates passed it, correctly by their own criteria. The audit
;; answered GAPS: none, because it compares the answer to the THESIS and the
;; thesis had itself drifted. The coverage check found every substantive token
;; in a confirmed artifact, because the answer WAS a confirmed artifact,
;; verbatim. Nothing in the harness asked whether it answers the question.

(def ^:private unwrap-problem
  (str "When can two-dimensional phase unwrapping be done exactly, and by a"
       " polynomial-time algorithm? Locate the noise threshold sigma at which"
       " exact recovery of the wrapped field on the torus becomes impossible,"
       " and say whether the unwrapper everyone ships attains it. That"
       " unwrapper minimises a weighted sum over integer flows on the dual"
       " grid, which is a minimum cost flow and runs in polynomial time."))

(deftest an-answer-with-nothing-of-the-problem-in-it-is-caught-without-a-judge
  ;; The free rung. Zero shared vocabulary is not a judgement call, and it is
  ;; the one case that still gets caught when the judge cannot be reached.
  (is (false? (tools/engages-problem?
               unwrap-problem
               "The five-element Sidon set {1, 2, 5, 11, 13} is optimal.")))
  (testing "an answer about the problem's own subject matter passes this rung"
    (is (true? (tools/engages-problem?
                unwrap-problem
                "Exact recovery holds for every sigma below 0.31."))))
  (testing "and so does the bad answer — lexical overlap cannot see relevance"
    ;; Which is exactly why this rung is not the fix, only the floor. The
    ;; shipped 4-cycle answer shares `flow` and `cost` with the problem.
    (is (true? (tools/engages-problem?
                unwrap-problem
                (str "On the 4-cycle there are two integer flows of minimum"
                     " cost, so the uniqueness lemma is false.")))))
  (testing "no problem statement means nothing to be irrelevant to"
    (is (true? (tools/engages-problem? nil "anything")))
    (is (true? (tools/engages-problem? "  " "anything")))))

(deftest relevance-refusal-names-what-the-answer-does-not-address
  (testing "a FAIL blocks and quotes both halves back"
    (let [b (tools/relevance-block
             {:verdict :fail
              :text (str "ASKS: the value of the noise threshold sigma and"
                         " whether min-cost flow attains it\n"
                         "SUPPLIES: a counterexample to a uniqueness lemma"
                         " on one 4-vertex graph\nVERDICT: FAIL")})]
      (is (some? b))
      (is (str/includes? b "noise threshold sigma"))
      (is (str/includes? b "4-vertex graph"))
      (is (re-find #"(?i)which .*did not settle|what you did not" b)
          "and names the way out, which is one turn's work")))
  (testing "a PASS does not block"
    (is (nil? (tools/relevance-block {:verdict :pass :text "VERDICT: PASS"}))))
  (testing "a judge that could not answer does not block"
    ;; Deliberately fail-OPEN, against the convention everywhere else here.
    ;; The other gates guard evidence, where a check that cannot run must not
    ;; wave a claim through. This one is editorial: the evidence rungs have
    ;; already passed by the time it runs, and stranding a verified answer
    ;; because a judge call died is the worse failure. `judge` retries an
    ;; unparseable answer three times and journals every attempt before it
    ;; gets here.
    (is (nil? (tools/relevance-block {:verdict :unparseable :text ""})))
    (is (nil? (tools/relevance-block {:verdict :ambiguous :text ""})))))

(deftest done-refuses-an-answer-to-a-different-question
  ;; End to end, with every other rung satisfied: a passing audit against this
  ;; exact text, an independent review, and full token coverage — the state
  ;; the phase-unwrapping run was in when it shipped.
  (let [answer (str "On the 4-cycle there are two integer flows of minimum"
                    " cost, so the uniqueness lemma is false.")
        b (branch-with :problem unwrap-problem
                       :thesis {:goal "characterise when the min-cost flow is unique"
                                :technique "t" :subClaims []}
                       :artifacts [{:claim answer :claim-status :confirmed
                                    :kind :smt :tier :slow :code "c"}]
                       :last-review {:passed true}
                       :last-audit {:passed true :proposed-answer answer
                                    :established answer :relaxation? false})
        prompts (atom [])]
    (with-redefs [llm/chat (fn [_ _ msgs _]
                             (swap! prompts conj (:content (second msgs)))
                             {:content (str "ASKS: a threshold sigma and whether"
                                            " min-cost flow attains it\n"
                                            "SUPPLIES: a counterexample about"
                                            " one 4-vertex graph\n"
                                            "VERDICT: FAIL")})]
      (let [r (tools/run-tool {:branch b :turn 1 :tool-name "done"
                               :args {:answer answer}})]
        (is (= :failure (:category r)))
        (is (not= :done (:status (:branch r))))
        (is (str/includes? (:result r) "threshold sigma")
            "the refusal names what the problem asked for")
        (is (str/includes? (str (first @prompts)) "polynomial-time algorithm")
            "the judge is shown the problem, which is what the audit never sees")
        (is (re-find #"(?i)partial" (str (first @prompts)))
            "and told that an honestly-scoped partial result is a PASS")))))

(deftest done-ships-an-answer-that-scopes-itself-honestly
  ;; The escape hatch, and the outcome this gate is actually for. The run did
  ;; not reach sigma; saying so and stating what it did reach is a legitimate
  ;; answer, and is worth more than the same evidence shipped as though it
  ;; were the whole thing.
  (let [answer (str "This does not locate the noise threshold sigma. What is"
                    " established is that the minimum-cost flow is not unique.")
        b (branch-with :problem unwrap-problem
                       :thesis {:goal "characterise when the min-cost flow is unique"
                                :technique "t" :subClaims []}
                       :artifacts [{:claim answer :claim-status :confirmed
                                    :kind :smt :tier :slow :code "c"}]
                       :last-review {:passed true}
                       :last-audit {:passed true :proposed-answer answer
                                    :established answer :relaxation? false})]
    (with-redefs [llm/chat (fn [& _] {:content "ASKS: sigma\nSUPPLIES: what it says it does\nVERDICT: PASS"})]
      (let [r (tools/run-tool {:branch b :turn 1 :tool-name "done"
                               :args {:answer answer}})]
        (is (= :success (:category r)))
        (is (= :done (:status (:branch r))))))))

(deftest the-relevance-judge-runs-last-and-only-once-the-evidence-holds
  ;; It is the one rung that costs a model call, so an answer that fails a
  ;; cheap deterministic rung must never reach it.
  (let [b (branch-with :problem unwrap-problem :artifacts [])]
    (with-redefs [llm/chat (fn [& _] (throw (ex-info "judge must not be called" {})))]
      (let [r (tools/run-tool {:branch b :turn 1 :tool-name "done"
                               :args {:answer "sigma is 0.31"}})]
        (is (= :failure (:category r)))
        (is (str/includes? (:result r) "no confirmed artifact"))))))

(deftest free-variables-detection
  (testing "a constant pinned to a literal is not free"
    (is (= [] (tools/free-variables "(declare-const a Int)(assert (= a 5))"))))
  (testing "an unpinned constant is free, so SAT over it is existential"
    (is (= ["b"] (tools/free-variables "(declare-const b Int)(assert (> b 0))"))))
  (testing "mixed"
    (is (= ["y"] (tools/free-variables
                  "(declare-const x Int)(declare-const y Int)(assert (= x 1))")))))

;; --- the tool surface -------------------------------------------------------

(deftest tool-table-is-complete
  ;; A stray closing paren silently truncated this table on the first build:
  ;; the namespace loaded, `require` succeeded, and seven of ten methods were
  ;; simply absent until a live run hit one. Pinning the set turns that into a
  ;; failing test instead of a mid-run crash.
  (is (= #{"add_rule" "retract_rule" "verify" "verify_smt" "verify_template"
           "thesis" "branch_theses" "review" "audit" "done" "give_up"
           "verify_lean" "lean_search" "proof_start" "proof_step"
           "proof_state" "proof_abandon"
           "octave_eval" "verify_octave" "measure" "fetch_artifact" "fetch_turn"}
         (set (tools/tool-names))))
  (is (some? (get-method tools/run-tool :default))
      "an unknown tool name must land somewhere that names the alternatives"))

;; --- the journal ------------------------------------------------------------

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(deftest journal-round-trips
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :provider :deepseek :model "m"})]
      (runs/open-branch! c rid {:branch-id "B1"})
      (journal/record-turn! c rid {:branch-id "B1" :turn 1 :tool-name "verify"
                                   :result "ok" :category "success"})
      (journal/record-artifact! c rid {:branch-id "B1" :turn 1 :kind :prolog
                                       :claim "every element of S satisfies P" :claim-status :confirmed})
      (is (= 1 (count (journal/turns c rid))))
      (is (= 1 (count (journal/confirmed-artifacts c rid "B1")))))))

(deftest gate-firing-id-is-settleable
  ;; record-gate! returned the EVENT id rather than the gate_firings id, so
  ;; every settle updated a row that did not exist and the whole tally stayed
  ;; permanently open. Caught by reading a live run's journal.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          _ (runs/open-branch! c rid {:branch-id "B1"})
          id (journal/record-gate! c rid {:branch-id "B1" :turn 1 :gate :milestone
                                          :prediction "calls review" :window 2})]
      (is (= 1 (count (journal/unsettled-gates c rid "B1"))))
      (journal/settle-gate! c id :met 2)
      (is (empty? (journal/unsettled-gates c rid "B1")))
      (is (= 1 (:met (first (journal/gate-tally c rid))))))))

(deftest failure-log-retrieves-by-similarity
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (failures/record! c rid {:branch-id "B1" :turn 1 :tool-name "verify_smt"
                               :claim "a sidon set of size 40 fits in [1,100]"
                               :reason "z3 returned unsat"})
      (failures/record! c rid {:branch-id "B2" :turn 1 :tool-name "verify"
                               :claim "the zebra puzzle has two solutions"
                               :reason "prolog found exactly one"})
      (is (= 1 (count (failures/similar c rid "is there a sidon set of size 40"))))
      (is (empty? (failures/similar c rid "lean mathlib induction")))
      (testing "an unusable query returns nothing rather than throwing"
        (is (empty? (failures/similar c rid "a b c")))
        (is (empty? (failures/similar c rid "")))))))

(deftest claim-registry-follows-the-ucla-protocol
  (let [r (claims/new-registry)]
    (testing "atomic double-claim: second branch is told who holds it"
      (is (= :claimed (claims/try-claim! r "B1" "the zebra owner drinks water")))
      (let [answer (claims/try-claim! r "B2" "The Zebra owner drinks WATER!")]
        (is (= :held (:status answer)) "normalization groups the phrasings")
        (is (= "B1" (:holder answer)))))
    (testing "a verdict settles the claim and stays, even a failing one"
      (claims/complete! r "B1" "the zebra owner drinks water" :failed "prolog said no")
      (let [answer (claims/try-claim! r "B2" "the zebra owner drinks water")]
        (is (= :done (:status answer)))
        (is (= :failed (:outcome answer)))
        (is (= "prolog said no" (:reason answer))
            "a failed verdict is information, not a slot to re-race")))
    (testing "release drops an in-flight claim so another branch may try"
      (is (= :claimed (claims/try-claim! r "B1" "n < 2^n for all n")))
      (claims/release! r "B1" "n < 2^n for all n")
      (is (= :claimed (claims/try-claim! r "B2" "n < 2^n for all n"))))
    (testing "only the holder may release"
      (claims/release! r "B1" "n < 2^n for all n")
      (is (= :held (:status (claims/try-claim! r "B3" "n < 2^n for all n")))
          "B1's release of B2's claim must not have dropped it"))))

(deftest slow-verification-is-not-spent-twice
  ;; Two branches, one claim, one judge call. The second branch is served the
  ;; first branch's verdict with provenance, spends nothing, and the dedup is
  ;; journaled. A nil registry means every tool runs as before — the rest of
  ;; the suite is that regression test.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          registry (claims/new-registry)
          mk (fn [id] (merge (state/new-branch {:id id :problem "p"})
                             {:artifacts [{:claim "the triple sums to 1000"
                                           :claim-status :confirmed
                                           :kind :smt :tier :confirmed :code "(x)"}]}))
          calls (atom 0)]
      (with-redefs [llm/chat (fn [& _]
                               (swap! calls inc)
                               {:content "GAPS: none\nVERDICT: PASS"})]
        (let [ra (tools/run-tool {:branch (mk "B1") :turn 1 :conn c :run-id rid
                                  :claims registry :tool-name "review"
                                  :args {:claim "the triple sums to 1000"
                                         :rationale "different encoding"}})
              rb (tools/run-tool {:branch (mk "B2") :turn 1 :conn c :run-id rid
                                  :claims registry :tool-name "review"
                                  :args {:claim "The triple sums to 1000."
                                         :rationale "yet another encoding"}})]
          (is (= :success (:category ra)))
          (is (= 1 @calls) "the second review spends no judge call")
          (is (= :success (:category rb)))
          (is (str/includes? (:result rb) "B1") "provenance names the source branch")
          (let [ev (first (filter #(= "verification-dedup-hit" (:kind %))
                                  (journal/events-since c rid 0)))]
            (is (some? ev))
            (is (str/includes? (:data ev) "B1"))))))))

(deftest the-same-claim-check-never-refuses-a-cross-check
  ;; The same-claim refusal is for a SECOND FIRST-VERDICT, and two tools it
  ;; must keep its hands off.
  ;;
  ;; `review` exists to re-examine ground the branch has already proved — its
  ;; body reads (last (state/confirmed-artifacts branch)) — so a check that
  ;; refuses claims already in the pool refuses review by construction. gen-23
  ;; made 8 refusals in its first 197 turns and 5 were review, each one
  ;; costing a branch the independent cross-check of a result it had just
  ;; verified.
  ;;
  ;; And a DIFFERENT engine reaching the same claim is not duplicate work at
  ;; all: consensus/engine-agreement counts distinct engine kinds precisely
  ;; because independent empirical checks compose where opinions do not. Two
  ;; z3 runs on one claim are a wasted call; z3 agreeing with Lean is the
  ;; evidence the whole tier system is built to collect.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          claim "every stage-2-optimal flow has |k_e| at most S on every edge"]
      (artifacts/record! c rid {:branch-id "B1" :turn 3 :kind :lean :tier :confirmed
                                :claim claim :code "theorem t : True := trivial"})
      (testing "review is never refused, however exactly the claim matches"
        (let [judged (atom 0)
              b (merge (state/new-branch {:id "B2" :problem "p"})
                       {:artifacts [{:claim claim :claim-status :confirmed
                                     :kind :lean :tier :confirmed :code "t"}]})]
          (with-redefs [llm/chat (fn [& _] (swap! judged inc)
                                   {:content "GAPS: none\nVERDICT: PASS"})]
            (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                     :claims (claims/new-registry)
                                     :tool-name "review"
                                     :args {:claim claim
                                            :rationale "a different formulation"}})]
              (is (not= :mechanics (:category r))
                  "review re-examines proved ground; that is its whole job")))))

      (testing "a different engine on the same claim is agreement, not duplication"
        (let [engine (atom 0)
              b (state/new-branch {:id "B3" :problem "p"})]
          (with-redefs [smt/run-smt (fn [& _] (swap! engine inc)
                                      {:status :ok :verdict :unsat})
                        llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
            (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                     :claims (claims/new-registry)
                                     :tool-name "verify_smt"
                                     :args {:claim claim :smtlib "(assert true)"
                                            :expectedVerdict "unsat"}})]
              (is (= 1 @engine) "z3 still runs on a claim only Lean has confirmed")
              (is (= :success (:category r)))))))

      (testing "the same engine twice on one claim is still refused"
        (artifacts/record! c rid {:branch-id "B1" :turn 4 :kind :smt :tier :confirmed
                                  :claim claim :code "(assert true)"})
        (let [engine (atom 0)
              b (state/new-branch {:id "B4" :problem "p"})]
          (with-redefs [smt/run-smt (fn [& _] (swap! engine inc)
                                      {:status :ok :verdict :unsat})
                        llm/chat (fn [& _] {:content "VERDICT: PASS"})]
            (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                     :claims (claims/new-registry)
                                     :tool-name "verify_smt"
                                     :args {:claim claim :smtlib "(assert true)"
                                            :expectedVerdict "unsat"}})]
              (is (= 0 @engine) "z3 is not asked a question z3 already answered")
              (is (= :mechanics (:category r))))))))))

(deftest a-lemma-already-proved-in-other-words-is-refused-not-re-run
  ;; vf-cak. The registry keys on spelling, so a lemma restated with renamed
  ;; variables is a different claim to it. gen-22 a#687 and a#689 are the same
  ;; coefficient-feasibility fact with B renamed to D = E*R — 4-gram Jaccard
  ;; 0.28, and BOTH on branch B3, which proved it twice. a#712 and a#717 sit
  ;; at 0.19 and a#717's own text says "(re-verified on this branch)": the
  ;; model had the neighbour in its ledger and re-proved it anyway, so
  ;; surfacing is not enough. Something has to decline.
  ;;
  ;; It declines rather than crediting. A judge that wrongly says "same" would
  ;; hand a branch a verdict it never earned, which in a claim-first harness is
  ;; worse than the duplicate engine call; the branch is the only party that
  ;; knows what it meant, so it gets the neighbour's exact words and decides.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (artifacts/record! c rid
                         {:branch-id "B3" :turn 11 :kind :smt :tier :confirmed
                          :claim (str "For any integers B >= 1, E >= 1, define Qmax = E*B*B,"
                                      " LR = 2*B, K = LR + 1, H = K*Qmax + LR + 1. Then"
                                      " LR < K and K*Qmax + LR < H.")
                          :code "(assert true)"})
      (let [engine (atom 0)
            judged (atom [])
            b (state/new-branch {:id "B2" :problem "p"})
            restated (str "For any integers E >= 1, R >= 1, define D = E*R, Qmax = E*D*D,"
                          " LR = 2*D, K = LR+1, H = K*Qmax+LR+1. Then LR < K and"
                          " K*Qmax + LR < H.")]
        (with-redefs [smt/run-smt (fn [& _] (swap! engine inc)
                                    {:status :ok :verdict :unsat})
                      llm/chat (fn [_a _b msgs & _r]
                                 (swap! judged conj (str msgs))
                                 ;; PASS means "same statement". The polarity is
                                 ;; forced by verdict/instruction's fail-closed
                                 ;; rule: an unsure judge answers FAIL, and the
                                 ;; safe reading of unsure is "let it verify".
                                 {:content "VERDICT: PASS"})]
          (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                   :claims (claims/new-registry)
                                   :tool-name "verify_smt"
                                   :args {:claim restated :smtlib "(assert true)"
                                          :expectedVerdict "unsat"}})]
            (is (= 0 @engine) "z3 is never asked a question already answered")
            (is (= :mechanics (:category r))
                "aiming at settled ground is not a failed line of inquiry")
            (is (nil? (:artifact r))
                "the branch is not credited with an artifact it did not earn")
            (is (str/includes? (:result r) "B3") "provenance names the source")
            (is (str/includes? (:result r) "Qmax = E*B*B")
                "and quotes the neighbour verbatim, so the branch can see what it is")
            (is (seq @judged) "the comparison went through a judge, not a threshold")))))))

(deftest a-merely-adjacent-lemma-is-not-mistaken-for-the-same-one
  ;; The false positive is the expensive direction, so the judge's default has
  ;; to be "different". These two share almost all of their vocabulary and are
  ;; separate facts — injectivity and surjectivity of the same map, gen-22
  ;; a#706 and a#710, which the run needed both of.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (artifacts/record! c rid
                         {:branch-id "B3" :turn 30 :kind :lean :tier :confirmed
                          :claim (str "The alternating snake order on an M×N grid is"
                                      " injective: snakepos(i,j) maps distinct cells to"
                                      " distinct positions.")
                          :code "theorem inj : True := trivial"})
      (let [engine (atom 0)
            b (state/new-branch {:id "B2" :problem "p"})]
        (with-redefs [smt/run-smt (fn [& _] (swap! engine inc)
                                    {:status :ok :verdict :unsat})
                      ;; FAIL to the same-claim question only. The
                      ;; faithfulness judge runs afterwards and must not be
                      ;; answered by the same stub, or the verification fails
                      ;; for an unrelated reason and the test proves nothing.
                      llm/chat (fn [_a _b msgs & _r]
                                 {:content (if (str/includes? (str msgs) "state the SAME fact")
                                             "VERDICT: FAIL"
                                             "GAPS: none\nVERDICT: PASS")})]
          (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                   :claims (claims/new-registry)
                                   :tool-name "verify_smt"
                                   :args {:claim (str "The alternating snake order on an M×N"
                                                      " grid is surjective: every position p"
                                                      " is snakepos(i,j) for some cell.")
                                          :smtlib "(assert true)"
                                          :expectedVerdict "unsat"}})]
            (is (= 1 @engine) "a different statement still gets its verification")
            (is (= :success (:category r)))))))))

(deftest the-same-claim-check-is-skipped-when-nothing-is-close
  ;; The prefilter is what keeps this affordable: no FTS candidate, no judge
  ;; call. A run whose pool holds nothing like the claim pays nothing.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          calls (atom 0)
          b (state/new-branch {:id "B1" :problem "p"})]
      (with-redefs [smt/run-smt (fn [& _] {:status :ok :verdict :unsat})
                    llm/chat (fn [& _] (swap! calls inc)
                               {:content "GAPS: none\nVERDICT: PASS"})]
        (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                         :claims (claims/new-registry)
                         :tool-name "verify_smt"
                         :args {:claim "the minimum cost is 2"
                                :smtlib "(assert true)" :expectedVerdict "unsat"}})
        (is (= 1 @calls)
            "one judge call — the faithfulness check — and no same-claim call")))))

(deftest every-slow-engine-consults-the-claim-registry
  ;; vf-3k4. The registry was reachable from `review` and `verify_template`
  ;; and from nothing else, in EITHER direction — the engines neither asked it
  ;; nor filled it. gen-22 ran 97 slow verifications and the dedup fired zero
  ;; times, while B3:t11 and B2.2:t17 sent Z3 the same claim, byte for byte.
  ;;
  ;; One case per engine, each asserting the thing that costs money: the
  ;; engine is not invoked at all on the second branch.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          claim "every stage-2-optimal flow has |k_e| at most S on every edge"
          mk (fn [id] (state/new-branch {:id id :problem "p"}))
          run (fn [registry id tool args]
                (tools/run-tool (merge {:branch (mk id) :turn 1 :conn c :run-id rid
                                        :claims registry :tool-name tool}
                                       {:args (assoc args :claim claim)})))]
      (testing "verify_smt"
        (let [registry (claims/new-registry)
              calls (atom 0)]
          (with-redefs [smt/run-smt (fn [& _] (swap! calls inc)
                                      {:status :ok :verdict :unsat})
                        llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
            (let [ra (run registry "B1" "verify_smt"
                          {:smtlib "(assert true)" :expectedVerdict "unsat"})
                  rb (run registry "B2" "verify_smt"
                          {:smtlib "(assert (not false))" :expectedVerdict "unsat"})]
              (is (= :success (:category ra)))
              (is (= :success (:category rb)))
              (is (= 1 @calls) "z3 is not run a second time on the same claim")
              (is (str/includes? (:result rb) "B1"))))))

      (testing "verify_lean"
        (let [registry (claims/new-registry)
              calls (atom 0)]
          (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                        lean-repl/mathlib-env (fn [& _] nil)
                        lean-pool/checkout! (fn [& _] {:id "s"})
                        lean-repl/run-command (fn [& _] (swap! calls inc)
                                                {:ok true :sorries []})
                        llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
            (let [ra (run registry "B1" "verify_lean" {:lean "theorem t : True := trivial"})
                  rb (run registry "B2" "verify_lean" {:lean "theorem u : True := by trivial"})]
              (is (= :success (:category ra)))
              (is (= :success (:category rb)))
              (is (= 1 @calls) "the Lean pool is not paid twice for one claim")))))

      (testing "verify_octave"
        (let [registry (claims/new-registry)
              calls (atom 0)]
          (with-redefs [octave/create-session (fn [& _] {:dir "/tmp/x"
                                                        :log (atom [{:code "x = 1;"}])
                                                        :alive (atom true)})
                        octave/check (fn [& _] (swap! calls inc)
                                       {:ok true :verdict true :tol 0 :exact true})
                        llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
            (let [ra (run registry "B1" "verify_octave" {:expr "x == 1"})
                  rb (run registry "B2" "verify_octave" {:expr "y == 1"})]
              (is (= :success (:category ra)))
              (is (= :success (:category rb)))
              (is (= 1 @calls) "octave is not run a second time on the same claim"))))))))

(deftest a-failed-proof-attempt-does-not-lock-the-claim
  ;; The rule the registry has to follow, and the one that would be quietly
  ;; catastrophic to get wrong. verify_lean already argues it for the artifact
  ;; table — "a snippet Lean rejects is a failed proof ATTEMPT, which says
  ;; nothing about whether the claim is true" — and the registry is the same
  ;; argument with a longer blast radius: a settled claim stays settled for the
  ;; whole run, so recording one branch's type error as a verdict would tell
  ;; every later branch that a TRUE claim had been checked and failed.
  ;;
  ;; Only a verdict about the claim settles it. A bad proof, an open `sorry`,
  ;; an unfaithful encoding and a dead engine all release.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          claim "every stage-2-optimal flow has |k_e| at most S on every edge"
          mk (fn [id] (state/new-branch {:id id :problem "p"}))
          run (fn [registry id tool args]
                (tools/run-tool (merge {:branch (mk id) :turn 1 :conn c :run-id rid
                                        :claims registry :tool-name tool}
                                       {:args (assoc args :claim claim)})))]
      (testing "Lean rejects B1's proof, so B2 may still try"
        (let [registry (claims/new-registry)
              calls (atom 0)]
          (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                        lean-repl/mathlib-env (fn [& _] nil)
                        lean-pool/checkout! (fn [& _] {:id "s"})
                        lean-repl/run-command
                        (fn [& _] (swap! calls inc)
                          (if (= 1 @calls)
                            {:ok false :sorries []
                             :errors [{:data "unknown identifier 'foo'"}]}
                            {:ok true :sorries []}))
                        llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
            (let [ra (run registry "B1" "verify_lean" {:lean "theorem t : True := foo"})
                  rb (run registry "B2" "verify_lean" {:lean "theorem t : True := trivial"})]
              (is (= :failure (:category ra)))
              (is (= :success (:category rb))
                  "B1's broken proof must not settle the claim against B2")
              (is (= 2 @calls) "B2 really ran Lean rather than being served a verdict")))))

      (testing "an open sorry is not a verdict either"
        ;; The snippet must not contain the literal `sorry` — lint/lint-lean
        ;; rejects that before the REPL is reached, which is a different
        ;; refusal. This is the case that gets past the lint: a declaration
        ;; that elaborates and leaves a goal open anyway.
        (let [registry (claims/new-registry)
              calls (atom 0)]
          (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                        lean-repl/mathlib-env (fn [& _] nil)
                        lean-pool/checkout! (fn [& _] {:id "s"})
                        lean-repl/run-command
                        (fn [& _] (swap! calls inc)
                          (if (= 1 @calls)
                            {:ok true :sorries [{:goal "True"}]}
                            {:ok true :sorries []}))
                        llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
            (run registry "B1" "verify_lean" {:lean "theorem t : True := openGoal"})
            (is (= :success (:category (run registry "B2" "verify_lean"
                                            {:lean "theorem t : True := trivial"})))))))

      (testing "an unfaithful encoding leaves the claim open for a correct one"
        ;; Keyed on which encoding the judge is looking at, not on call order:
        ;; the faithfulness check runs a structural pass first and does not
        ;; always reach the model, so a counter here measures the wrong thing.
        (let [registry (claims/new-registry)]
          (with-redefs [smt/run-smt (fn [& _] {:status :ok :verdict :unsat})
                        llm/chat (fn [_a _b msgs & _rest]
                                   {:content
                                    (if (str/includes? (str msgs) "the-wrong-question")
                                      "GAP: the encoding proves something else\nVERDICT: FAIL"
                                      "GAPS: none\nVERDICT: PASS")})]
            (run registry "B1" "verify_smt" {:smtlib "(assert the-wrong-question)"
                                             :expectedVerdict "unsat"})
            (is (= :success (:category (run registry "B2" "verify_smt"
                                            {:smtlib "(assert (<= k S))"
                                             :expectedVerdict "unsat"})))
                "B1 encoded the wrong question; that is not a verdict on the claim"))))

      (testing "a refutation IS a verdict and does settle"
        (let [registry (claims/new-registry)
              calls (atom 0)]
          (with-redefs [smt/run-smt (fn [& _] (swap! calls inc)
                                      {:status :ok :verdict :sat})
                        llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
            (let [ra (run registry "B1" "verify_smt"
                          {:smtlib "(assert (= x 1))" :expectedVerdict "unsat"})
                  rb (run registry "B2" "verify_smt"
                          {:smtlib "(assert (= y 1))" :expectedVerdict "unsat"})]
              (is (= :failure (:category ra)))
              (is (= :failure (:category rb)))
              (is (= 1 @calls) "the refutation carries over instead of being re-run")
              (is (str/includes? (:result rb) "B1")))))))))

(deftest shared-artifact-log-round-trips
  ;; The failure log's twin: what an engine CONFIRMED, with provenance inline.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (artifacts/record! c rid {:branch-id "B1" :turn 2 :kind :smt :tier :confirmed
                                :claim "the pythagorean triple summing to 1000"
                                :code "(check-sat)"})
      (artifacts/record! c rid {:branch-id "B2" :turn 3 :kind :prolog :tier :fast
                                :claim "the zebra owner drinks water"
                                :code "zebra(X)"})
      (is (= "B1" (:branch_id (first (artifacts/similar c rid "pythagorean triple 1000")))))
      (is (empty? (artifacts/similar c rid "lean mathlib induction")))
      (is (= 2 (count (artifacts/recent c rid))))
      (testing "render carries provenance: branch, engine, tier"
        (let [text (artifacts/render (artifacts/recent c rid))]
          (is (str/includes? text "engine-verified"))
          (is (str/includes? text "[B1 smt/confirmed]"))
          (is (str/includes? text "[B2 prolog/fast]")))))))

(deftest shared-artifacts-reach-only-other-branches
  ;; The read side: cross-branch hits enter the context block and are
  ;; journaled; a branch's own lemmas do not come back at it, and with the
  ;; flag off the shared log is invisible.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          b1 (branch-with :id "B1")]
      (artifacts/record! c rid {:branch-id "B1" :turn 1 :kind :smt :tier :confirmed
                                :claim "own lemma about sidon sets" :code ""})
      (artifacts/record! c rid {:branch-id "B2" :turn 2 :kind :prolog :tier :confirmed
                                :claim "cross-branch lemma about sidon sets" :code ""})
      (let [{:keys [block]} (#'aloop/context-block c rid b1 "sidon sets" true)]
        (is (str/includes? block "cross-branch lemma"))
        (is (not (str/includes? block "own lemma"))
            "a branch re-reading its own lemmas is noise"))
      (testing "each hit is journaled with its source branch"
        (let [ev (first (filter #(= "shared-artifact-hit" (:kind %))
                                (journal/events-since c rid 0)))]
          (is (some? ev))
          (is (str/includes? (:data ev) "B2"))))
      (testing "flag off, shared log invisible"
        (let [{:keys [block]} (#'aloop/context-block c rid b1 "sidon sets" false)]
          (is (or (nil? block)
                  (not (str/includes? block "cross-branch lemma")))))))))

(deftest only-on-topic-confirmations-enter-the-shared-pool
  ;; The write side, and the reason vf-8fl is worse with sharing on than
  ;; without it: an artifact that only ever cost its own branch a turn becomes
  ;; every branch's context. Observed live, four turns into run 0d0c3560.
  (let [problem "Characterise the non-uniqueness of the L1 min-cost-flow unwrapping."
        b (branch-with :problem problem)
        confirmed (fn [claim] {:claim claim :claim-status :confirmed :kind :prolog})]
    (is (true? (aloop/shareable? b (confirmed "the two-path gadget has exactly two optimal flows") true)))
    (is (false? (aloop/shareable? b (confirmed "Diagnostic: between(-1,1,X) succeeds for X = -1,0,1.") true))
        "a check of the harness's own tooling is nobody else's lemma")
    (testing "the existing conditions still hold"
      (is (false? (aloop/shareable? b (confirmed "the two-path gadget has exactly two optimal flows") false))
          "not with the flag off")
      (is (false? (aloop/shareable? b {:claim "the two-path gadget has exactly two optimal flows"
                                       :claim-status :refuted :kind :prolog} true))
          "and only confirmations ever cross"))))

(deftest shared-artifact-hit-journals-once-per-pair
  ;; A 28-turn run produced 86 hit events for a 15-row pool: the block
  ;; re-renders every turn, so per-serving journaling counted turns, not
  ;; sharing. Only the FIRST serving of an artifact to a branch is journaled;
  ;; the artifact itself keeps re-entering the context.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          hits #(count (filter (fn [e] (= "shared-artifact-hit" (:kind e)))
                               (journal/events-since c rid 0)))]
      (artifacts/record! c rid {:branch-id "B2" :turn 1 :kind :smt :tier :confirmed
                                :claim "shared lemma about sidon sets" :code ""})
      (let [{b1 :branch} (#'aloop/context-block c rid (branch-with :id "B1")
                                                "sidon sets" true)
            {:keys [block]} (#'aloop/context-block c rid b1 "sidon sets" true)]
        (is (= 1 (hits)) "re-serving the same artifact journals nothing new")
        (is (str/includes? block "shared lemma")
            "dedup is for the journal only; the artifact still re-enters context"))
      (testing "another branch is a new pair and gets its own hit"
        (#'aloop/context-block c rid (branch-with :id "B3") "sidon sets" true)
        (is (= 2 (hits)))))))

(deftest events-are-readable-by-cursor
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (runs/open-branch! c rid {:branch-id "B1"})
      (journal/record-turn! c rid {:branch-id "B1" :turn 1 :tool-name "verify"
                                   :result "ok" :category "success"})
      (let [all (journal/events-since c rid 0)]
        (is (= ["run-started" "branch-opened" "turn"] (map :kind all)))
        (is (= 1 (count (journal/events-since c rid (:id (second all)))))
            "a cursor returns strictly what follows it")))))

;; --- residual objectives ----------------------------------------------------

(deftest residual-reports-what-is-outstanding
  ;; A run cut short should say what is unfinished rather than making a resume
  ;; re-derive scope from the transcript (dirge PR 738).
  (let [b (branch-with :thesis {:goal "prove P" :subClaims ["lemma A" "lemma B"]}
                       :artifacts [{:claim "lemma A" :claim-status :confirmed}])
        r (state/residual b)]
    (is (= ["lemma A"] (:proved r)))
    (is (= ["lemma B"] (:outstanding r)))
    (is (str/includes? (state/render-residual r) "lemma B"))))

(deftest exhaustion-report-never-ships-a-lie
  ;; The full residual report for a run that exhausted without shipping.
  ;; Never ship nothing, never ship a lie: only engine-confirmed artifacts
  ;; render as established, and every other bucket is labeled for what it
  ;; does not substantiate. Pure — scripted branch states, no model, no store.
  (let [b1 (branch-with :id "B1"
                        :thesis {:goal "prove G" :technique "t"
                                 :subClaims ["lemma A" "lemma B"]}
                        :artifacts [{:claim "lemma A" :claim-status :confirmed
                                     :kind :smt :tier :confirmed :turn 2}
                                    {:claim "some coloring exists"
                                     :claim-status :existential
                                     :kind :smt :tier :fast :turn 3}
                                    {:claim "refuted approach D"
                                     :claim-status :refuted
                                     :kind :prolog :tier :fast :turn 4}])
        b2 (branch-with :id "B2"
                        :thesis {:goal "prove G" :technique "t2" :subClaims []}
                        :last-audit {:passed true
                                     :established "G for n < 5"
                                     :relaxation? true})
        report (state/build-residual-report
                {:branches [b1 b2]
                 :failures [{:branch_id "B1" :turn 4 :tool_name "verify"
                             :claim "approach D" :reason "goal failed"}]
                 :gate-tally [{:gate "wind-down" :fired 1 :met 0 :unmet 1 :open 0}]})
        text (state/render-residual-report report)
        [r1 r2] (:branches report)]
    (testing "the label says what this is on its face"
      (is (str/includes? (:label report) "not a solution"))
      (is (str/includes? text "PROGRESS REPORT")))
    (testing "buckets separate what substantiates from what does not"
      (is (= ["lemma A"] (map :claim (:established r1))))
      (is (= ["some coloring exists"] (map :claim (:existential r1))))
      (is (= ["lemma B"] (:outstanding r1)))
      (is (= ["lemma A"] (:proved r1))))
    (testing "a refuted artifact never renders anywhere"
      (is (not (str/includes? text "refuted approach D"))))
    (testing "existential artifacts are labeled as non-witnesses in the text"
      (is (str/includes? text "confirmed existence, not an instance")))
    (testing "thesis drift reports established against goal, no re-pinning"
      (is (= "G for n < 5" (get-in r2 [:drift :established])))
      (is (true? (get-in r2 [:drift :relaxation?])))
      (is (str/includes? text "strictly weaker than the goal")))
    (testing "run-level sections ride along"
      (is (str/includes? text "Shared failure log"))
      (is (str/includes? text "wind-down: 1 fired")))))

;; --- progress must mean progress -------------------------------------------

(deftest confirmation-alone-is-not-progress
  ;; Found by a zebra run that exhausted its turns. At turn 11 the model
  ;; verified "clpfd is available and supports a basic finite-domain
  ;; constraint", an engine said yes, and the harness recorded a confirmed
  ;; artifact and fired the milestone gate. Nothing about the puzzle had been
  ;; established. A guard that credits every confirmation cannot see a branch
  ;; verifying its own tooling.
  (let [b (branch-with :thesis {:goal "identify who drinks water and who owns the zebra"
                                :subClaims ["a clpfd model covers all fifteen clues"
                                            "the model yields a unique assignment"]})]
    (testing "a claim about the tooling does not advance the plan"
      (is (not (state/advances-thesis? b "clpfd is available and supports a basic finite-domain constraint")))
      (is (not (state/advances-thesis? b "the prolog session works"))))

    (testing "a claim about the actual problem does"
      (is (state/advances-thesis? b "the fifteen clues force a unique assignment"))
      (is (state/advances-thesis? b "the Norwegian drinks water and the Japanese owns the zebra")))

    (testing "the stall counter keeps climbing through a hollow confirmation"
      (let [after (state/record-outcome (assoc b :turns-since-progress 2)
                                        {:category :success :progress? true
                                         :claim "clpfd is available"})]
        (is (= 3 (:turns-since-progress after)))
        (is (not (:any-progress? after)))))

    (testing "and resets on a real one"
      (let [after (state/record-outcome (assoc b :turns-since-progress 2)
                                        {:category :success :progress? true
                                         :claim "the fifteen clues force a unique assignment"})]
        (is (= 0 (:turns-since-progress after)))
        (is (:any-progress? after)))))

  (testing "with no thesis registered, the problem statement stands in"
    ;; vf-8fl. Returning true unconditionally was defensible while the only
    ;; consumer was the stall counter. It is not defensible now: this is the
    ;; harness's only relevance signal, and a branch that never called `thesis`
    ;; had no guard at all. Observed live — B3 of run 0d0c3560 confirmed
    ;; "Diagnostic: between(-1,1,X) succeeds for X = -1,0,1." at turn 4, was
    ;; congratulated by the milestone gate for it, and exported it to all three
    ;; siblings through the shared pool.
    (let [b (branch-with :problem (str "Characterise how non-unique the L1"
                                       " minimum-cost-flow unwrapping is, and"
                                       " exhibit a canonical tie-breaking rule."))]
      (is (not (state/advances-thesis? b "Diagnostic: between(-1,1,X) succeeds for X = -1,0,1.")))
      (is (state/advances-thesis? b "the two-path gadget has exactly two optimal flows")
          (str "exploration that engages the problem is still credited, which"
               " is the whole reason this was permissive to begin with"))))

  (testing "a problem with no vocabulary of its own credits everything"
    ;; Nothing to be irrelevant to. Keeps the permissive behaviour wherever
    ;; there is genuinely nothing to measure against.
    (is (state/advances-thesis? (branch-with) "anything at all"))
    (is (state/advances-thesis? (state/new-branch {:id "B1"}) "anything at all"))))

(deftest an-off-topic-confirmation-fires-no-gate-and-crosses-no-branch
  ;; The other two consequences of the same hole. The milestone and branch-out
  ;; gates both read "this branch has confirmed something" as their fitness
  ;; signal, and with sharing on, a confirmation is exported to every sibling.
  ;; A tooling diagnostic should trip none of that.
  (let [problem "Characterise the non-uniqueness of the L1 min-cost-flow unwrapping."
        tooling {:claim "Diagnostic: between(-1,1,X) succeeds for X = -1,0,1."
                 :claim-status :confirmed :kind :prolog :tier :fast :turn 4}
        real {:claim "the two-path gadget has exactly two optimal flows"
              :claim-status :confirmed :kind :prolog :tier :fast :turn 4}
        with (fn [a] (branch-with :problem problem :artifacts [a]
                                  :turns (vec (repeat 5 {}))))]
    (testing "the branch has a confirmation either way — that is not the question"
      (is (state/has-confirmed? (with tooling))))
    (testing "but only a relevant one counts as one"
      (is (not (state/has-relevant-confirmed? (with tooling))))
      (is (state/has-relevant-confirmed? (with real))))
    (testing "so the milestone gate stays silent on the tooling check"
      (is (not ((:when (gates/by-name :milestone)) {:branch (with tooling)})))
      (is ((:when (gates/by-name :milestone)) {:branch (with real)})))
    (testing "and branch-out does not spend a fork on it"
      (let [ctx (fn [b] {:branch b :branch-count 1 :max-turns 40})]
        (is (not ((:when (gates/by-name :branch-out)) (ctx (with tooling)))))
        (is ((:when (gates/by-name :branch-out)) (ctx (with real))))))))

;; --- safe state -------------------------------------------------------------

(deftest safe-state-coverage-gate
  ;; DS1's third rung. dirge's version restores from a snapshot store only
  ;; after proving against git that the store covers every file changed since
  ;; green, because restoring around an untracked mutation produces a tree that
  ;; never existed. The analogue is an anonymous Prolog assert: permanent,
  ;; unnamed, and not undoable.
  (let [green [{:code "a." :name "r1"} {:code "b." :name "r2"}]]

    (testing "no green point means nothing to fall back to"
      (is (false? (:ok (state/snapshot-covers? (branch-with) green)))))

    (testing "covered when everything since the green point is named"
      (let [b (state/mark-green (branch-with) green)
            now (conj green {:code "c." :name "r3"})]
        (is (:ok (state/snapshot-covers? b now)))
        (is (= 1 (:rewinding (state/snapshot-covers? b now))))))

    (testing "DECLINES when an anonymous assert landed since the green point"
      (let [b (state/mark-green (branch-with) green)
            now (conj green {:code "c."})]
        (is (false? (:ok (state/snapshot-covers? b now))))
        (is (str/includes? (:reason (state/snapshot-covers? b now)) "anonymous"))))

    (testing "declines when the snapshot is not a prefix of the current log"
      (let [b (state/mark-green (branch-with) green)]
        (is (false? (:ok (state/snapshot-covers? b [{:code "z." :name "other"}]))))))

    (testing "the rung is harder to trip than a cull"
      (let [b (state/mark-green (branch-with) green)]
        (is (not (state/safe-state-due? (assoc b :consecutive-failures 3) 3)))
        (is (state/safe-state-due? (assoc b :consecutive-failures 6) 3))))

    (testing "and never trips without a green point at all"
      (is (not (state/safe-state-due? (branch-with :consecutive-failures 99) 3))))))

;; --- forking ----------------------------------------------------------------

(deftest branch-theses-splits-the-first-from-the-rest
  (let [b (branch-with)
        r (tools/run-tool {:branch b :turn 1 :tool-name "branch_theses"
                           :args {:theses [{:goal "route A" :technique "induction"}
                                           {:goal "route B" :technique "algebra"}
                                           {:goal "route C" :technique "search"}]}})]
    (testing "the first commits this branch, the rest become pending siblings"
      (is (= "route A" (get-in r [:branch :thesis :goal])))
      (is (= ["route B" "route C"] (mapv :goal (get-in r [:branch :pending-branch-theses])))))

    (testing "a tool never creates a branch itself"
      (is (nil? (:children r)) "the scheduler owns the branch table"))

    (testing "malformed proposals are refused rather than partially applied"
      ;; :mechanics since vf-v6x. A proposal the harness cannot parse says
      ;; nothing about the branch's mathematics, and this exact complaint was
      ;; 16 of the campaign's failure-turns.
      (are [theses] (= :mechanics (:category (tools/run-tool
                                            {:branch b :turn 1 :tool-name "branch_theses"
                                             :args {:theses theses}})))
        []
        "not a list"
        [{:no-goal true}]
        (vec (repeat 9 {:goal "too many"}))))))

;; --- the beam scheduler -----------------------------------------------------

(deftest scheduler-spawns-siblings-under-the-cap
  ;; The live beam culled a branch and shared its failure log, but the model
  ;; never chose to fork, so the fork path is proven here instead of by
  ;; contriving a prompt that provokes one.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          cfg {:engines {:swipl {:bin "swipl"}}}
          sessions (atom [])
          ctx {:conn c :run-id rid :config cfg :problem "p" :sessions sessions}
          parent (-> (state/new-branch {:id "B1" :problem "p"})
                     (assoc :pending-branch-theses
                            [{:goal "route B"} {:goal "route C"}]))]
      (try
        (runs/open-branch! c rid {:branch-id "B1"})
        (testing "children are opened with the parent recorded"
          (let [[children updated] (#'beam/spawn-children! ctx parent 1 3)]
            (is (= 2 (count children)))
            (is (= ["B1.2" "B1.3"] (mapv :id children)))
            (is (every? #(= "B1" (:parent-id %)) children))
            (is (nil? (:pending-branch-theses updated))
                "the parent's request is cleared so it cannot fork twice")
            (is (= "route B" (get-in (first children) [:thesis :goal])))
            (is (every? #(some? (:prolog %)) children)
                "each child gets its own engine session")
            (testing "and the branch table knows about them"
              (is (= #{"B1" "B1.2" "B1.3"} (set (map :id (runs/branches c rid))))))))

        (testing "the cap binds, and the parent is told rather than silently trimmed"
          (let [[children updated] (#'beam/spawn-children!
                                    ctx parent (gates/threshold :max-total-branches) 3)]
            (is (empty? children))
            (is (str/includes? (:content (last (:messages updated))) "cap"))))

        (finally
          (doseq [s @sessions] (veriframe.engine.prolog/dispose! s)))))))

(deftest cull-protects-a-recently-productive-branch
  ;; Incremental strategies look like verify N, fail at N+1, verify N+1.
  ;; Culling them throws away the most productive branch in the beam.
  ;; Ten turns: past the juvenile grace period, so this exercises the
  ;; ordinary cull path rather than the newborn protection.
  (let [failing (-> (branch-with :consecutive-failures 3)
                    (assoc :turns (vec (repeat 10 {}))))
        productive (assoc failing :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                                               :turn 5}])]
    (is (= :culled (:status (#'beam/cull-or-keep {} failing 2 []))))
    (is (= :active (:status (#'beam/cull-or-keep {} productive 2 []))))
    (testing "a stale confirmation does not protect it forever"
      (let [stale (assoc failing :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed
                                              :turn 0}])]
        (is (= :culled (:status (#'beam/cull-or-keep {} stale 2 []))))))
    (testing "the last branch standing is never culled"
      ;; Found by the width sweep: the width-1 arm was culled at turn 9 of 12
      ;; and the run ended there, which reads as evidence against narrow beams
      ;; and is actually a rule fired outside the situation it was written for.
      (is (= :active (:status (#'beam/cull-or-keep {} failing 0 [])))))
    (testing "a recent measurement protects it too"
      ;; vf-0of. A branch locating something empirically confirms nothing by
      ;; construction, so the confirmation-only trigger culled exactly the
      ;; branch whose thesis was the measurement.
      (let [measuring (assoc failing :artifacts [{:claim "the rate at sigma = 0.7 is 0.72"
                                                  :claim-status :empirical :turn 5}])]
        (is (= :active (:status (#'beam/cull-or-keep {} measuring 2 []))))))))

;; --- the evolutionary loop --------------------------------------------------

(deftest branch-out-fires-on-a-confirmation-with-room-to-grow
  ;; Variation on success: a branch that just proved something is the one
  ;; worth reproducing from. Selection without reproduction is half a
  ;; genetic algorithm, and forked:0 across every live run was the symptom.
  ;; Ordering matters and is deliberate: `milestone` outranks this rung, so
  ;; the first confirmation sends the branch to review rather than straight
  ;; to forking. Result, then review, THEN widen — reproduce from a result
  ;; that survived a check, not from a raw one.
  (let [fit (branch-with :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed :turn 5}]
                         :turns (vec (repeat 6 {}))
                         :gate-history [{:gate :milestone :turn 5}])]
    (testing "the first confirmation goes to review, not to forking"
      (is (= :milestone (:gate (arbiter/decide
                                {:branch (dissoc fit :gate-history)
                                 :max-turns 40 :branch-count 3})))))
    (testing "fires when the beam has room"
      (let [d (arbiter/decide {:branch fit :max-turns 40 :branch-count 3})]
        (is (= :branch-out (:gate d)))
        (is (str/includes? (:message d) "branch_theses"))
        (is (str/includes? (:prediction d) "branch_theses"))))
    (testing "stays silent with nothing confirmed"
      (is (not-any? #{:branch-out}
                    (map :gate (arbiter/eligible
                                {:branch (branch-with) :max-turns 40
                                 :branch-count 3})))))
    (testing "stays silent when the beam is at its cap"
      (is (not-any? #{:branch-out}
                    (map :gate (arbiter/eligible
                                {:branch fit :max-turns 40
                                 :branch-count (gates/threshold :max-total-branches)})))))
    (testing "stays silent once winding down — no new lines that late"
      (is (not-any? #{:branch-out}
                    (map :gate (arbiter/eligible
                                {:branch (assoc fit :turns (vec (repeat 38 {})))
                                 :max-turns 40 :branch-count 3})))))
    (testing "the prediction settles when the branch actually forks"
      (is (= :met (arbiter/settle {:gate :branch-out :turn 5 :window 3}
                                  {:current-turn 6 :tools-called ["branch_theses"]
                                   :branch-before fit :branch-after fit})))
      (is (= :unmet (arbiter/settle {:gate :branch-out :turn 5 :window 3}
                                    {:current-turn 9 :tools-called ["verify"]
                                     :branch-before fit :branch-after fit}))))))

(deftest a-forked-child-inherits-across-lineages
  ;; Crossover. A child that only inherits its parent's line is mutation;
  ;; recombination means it opens holding what the OTHER branches proved,
  ;; so a fork can combine two lineages rather than deepening one.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (journal/record-artifact! c rid {:branch-id "B2" :turn 3 :kind :smt
                                       :claim "sibling lemma about residues"
                                       :code "(check-sat)"
                                       :claim-status :confirmed :tier :fast})
      (journal/record-artifact! c rid {:branch-id "B1" :turn 4 :kind :prolog
                                       :claim "own lemma from the parent"
                                       :code "x" :claim-status :confirmed :tier :fast})
      (let [msg (#'beam/crossover-block c rid "B1")]
        (is (str/includes? msg "sibling lemma about residues")
            "the child opens holding what other lineages proved")
        (is (not (str/includes? msg "own lemma from the parent"))
            "its own line is already in its inherited history")))))

(deftest a-run-can-keep-exploring-after-a-branch-ships
  ;; Winner-takes-all ends a research run at the first qualifying answer.
  ;; With the flag off, a shipped branch goes inactive holding its answer
  ;; and the rest keep working; the best is chosen at the end.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :beam-width 2})
          shipped (assoc (branch-with :id "B1") :final-answer "first answer")
          working (branch-with :id "B2")]
      (runs/open-branch! c rid {:branch-id "B1"})
      (runs/open-branch! c rid {:branch-id "B2"})
      (testing "stop-on-first-done? true ends the run"
        (is (some? (#'beam/finish-now? {:config {:run {:stop-on-first-done? true}}}
                                       shipped [shipped working]))))
      (testing "false keeps going while another branch is alive"
        (is (nil? (#'beam/finish-now? {:config {:run {:stop-on-first-done? false}}}
                                      shipped [shipped working]))))
      (testing "false still ends once nobody is left to explore"
        (is (some? (#'beam/finish-now?
                    {:config {:run {:stop-on-first-done? false}}}
                    shipped [shipped (assoc working :status :culled)])))))))

(deftest a-shipped-branch-is-recorded-as-finished
  ;; vf-7hz. `done` sets {:status :done :final-answer ...}, and both places
  ;; that wrote a branch's ending missed exactly that pair: the post-cull
  ;; loop skipped anything holding a final answer, and the run-end loop only
  ;; wrote branches still `active?`. So a branch that SHIPPED — the one
  ;; outcome the run exists to produce — kept status 'active' in the record
  ;; and never emitted branch-closed.
  ;;
  ;; Gen-10 of the covering campaign completed with all nine surviving
  ;; branches shipped and all nine still reading 'active'. The GUI folds
  ;; branch status from branch-closed events, so a finished run drew nine
  ;; live branches, and the working indicator keys on :active as well.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :beam-width 3})
          status-of (fn [id] (-> (db/fetch c ["SELECT status, inactive_reason
                                               FROM branches
                                               WHERE run_id = ? AND id = ?" rid id])
                                 first))]
      (doseq [id ["B1" "B2" "B3"]]
        (runs/open-branch! c rid {:branch-id id :created-at-turn 0}))
      (#'beam/record-inactive!
       {:conn c :run-id rid}
       [(assoc (branch-with :id "B1") :status :done :final-answer "the answer")
        (assoc (branch-with :id "B2") :status :culled
               :inactive-reason "culled after 3 consecutive failures")
        (branch-with :id "B3")])
      (testing "a shipped branch is written as done, not left active"
        (is (= "done" (:status (status-of "B1")))))
      (testing "an ordinary cull still records why"
        (is (= "culled" (:status (status-of "B2"))))
        (is (str/includes? (:inactive_reason (status-of "B2")) "consecutive")))
      (testing "a branch still working is untouched"
        (is (= "active" (:status (status-of "B3")))))
      (testing "shipping emits branch-closed, which is what the GUI folds"
        (let [closed (filter #(= "branch-closed" (:kind %))
                             (journal/events-since c rid 0))]
          (is (= #{"B1" "B2"} (set (map :branch_id closed)))
              "exactly the two that stopped, and no event for the live one"))))))

;; --- the critic and Pareto retention ----------------------------------------

(deftest critic-score-parsing
  (testing "all four objectives, line-anchored, reasoning stripped"
    (is (= {:progress 4 :momentum 3 :distinctness 5 :viability 4}
           (critic/parse-scores
            (str "<think>SCORE progress: 1 maybe</think>\n"
                 "SCORE progress: 4\nSCORE momentum: 3\n"
                 "SCORE distinctness: 5\nSCORE viability: 4")))))
  (testing "a missing objective fails closed"
    (is (nil? (critic/parse-scores "SCORE progress: 4\nSCORE momentum: 3"))))
  (testing "out of range is not a score"
    (is (nil? (critic/parse-scores
               (str "SCORE progress: 7\nSCORE momentum: 3\n"
                    "SCORE distinctness: 5\nSCORE viability: 4")))))
  (testing "drafts: the last line wins"
    (is (= 2 (:progress (critic/parse-scores
                         (str "SCORE progress: 4\nSCORE momentum: 3\n"
                              "SCORE distinctness: 5\nSCORE viability: 4\n"
                              "SCORE progress: 2")))))))

(deftest branch-out-waits-before-asking-again
  ;; Gen-9: the rung fired on turns 14, 15 and 16 — one fork, then two
  ;; nagging re-fires — and the branch's whole fork budget was gone by turn
  ;; 16 of a 65-turn run. A gate that re-fires while the branch is still
  ;; acting on it spends the budget on repetition, which is what re-fire
  ;; guards exist for.
  (let [fit (fn [now last-fired]
              (branch-with :artifacts [{:claim "every element of S satisfies P" :claim-status :confirmed :turn 5}]
                           :turns (vec (repeat now {}))
                           :gate-history (into [{:gate :milestone :turn 1}]
                                               (when last-fired
                                                 [{:gate :branch-out :turn last-fired}]))))]
    (testing "silent immediately after firing"
      (is (not-any? #{:branch-out}
                    (map :gate (arbiter/eligible {:branch (fit 12 11) :max-turns 40
                                                  :branch-count 3})))))
    (testing "eligible again once the cooldown has passed"
      (is (some #{:branch-out}
                (map :gate (arbiter/eligible
                            {:branch (fit (+ 11 (gates/threshold :branch-out-cooldown)) 11)
                             :max-turns 40 :branch-count 3})))))
    (testing "the budget allows a productive branch several forks over a run"
      (is (>= (gates/threshold :max-branch-outs) 5)))))

(deftest domination-ignores-accumulated-progress
  ;; Survival is about where a line is going, not what it has banked. The
  ;; artifacts a branch already confirmed are in the log and cannot be lost
  ;; by culling it, while `progress` is cumulative and therefore rises with
  ;; age — comparing on it lets any mature branch dominate any young one
  ;; indefinitely, which is the age bias that outlives the grace period.
  (let [young {:progress 1 :momentum 4 :distinctness 4 :viability 4}
        mature {:progress 5 :momentum 4 :distinctness 4 :viability 4}]
    (is (not (critic/dominated? young [mature]))
        "more banked work alone does not dominate")
    (is (critic/dominated? {:progress 5 :momentum 2 :distinctness 2 :viability 2}
                           [{:progress 1 :momentum 4 :distinctness 4 :viability 4}])
        "a branch going nowhere is dominated however much it banked")))

(deftest critic-domination
  (let [a {:progress 4 :momentum 4 :distinctness 3 :viability 4}
        b {:progress 3 :momentum 3 :distinctness 3 :viability 4}
        c {:progress 1 :momentum 1 :distinctness 5 :viability 2}]
    (is (critic/dominated? b [a]) "worse somewhere, better nowhere: dominated")
    (is (not (critic/dominated? a [b])))
    (is (not (critic/dominated? c [a b])) "a unique strength survives")
    (is (not (critic/dominated? a [a])) "an equal vector does not dominate")))

(deftest critic-scoring-is-fail-closed
  (let [b (branch-with :thesis {:goal "g" :technique "t" :subClaims []})]
    (with-redefs [llm/chat (fn [& _]
                             {:content (str "SCORE progress: 4\nSCORE momentum: 3\n"
                                            "SCORE distinctness: 5\nSCORE viability: 4")})]
      (is (= {:progress 4 :momentum 3 :distinctness 5 :viability 4}
             (:scores (critic/score! {} b [] 7)))))
    (with-redefs [llm/chat (fn [& _] {:content "the branch seems fine to me"})]
      (is (nil? (critic/score! {} b [] 7))
          "an unusable critic answer is no information, not a random vector"))
    (with-redefs [llm/chat (fn [& _] (throw (ex-info "provider down" {})))]
      (is (nil? (critic/score! {} b [] 7))
          "a dead provider must not take the beam down"))))

(deftest a-juvenile-branch-is-not-culled-against-its-elders
  ;; Gen-9 forked for the first time and every child died within twelve
  ;; turns. The reason was structural, not intellectual: progress and
  ;; momentum are age-correlated, so a branch born at turn 15 scores 1 on
  ;; progress by definition and its fifteen-turn-old parent dominates it on
  ;; every objective one turn later. Selection that runs before an offspring
  ;; can express itself is not selection. A branch gets a grace period of
  ;; its own turns before the cull rule applies to it at all.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          ctx {:conn c :run-id rid}
          mature {:progress 5 :momentum 5 :distinctness 5 :viability 4}
          newborn (fn [turns]
                    (-> (branch-with :id "B1.2" :consecutive-failures 3
                                     :created-at-turn 15
                                     :critic {:scores {:progress 1 :momentum 2
                                                       :distinctness 5 :viability 4}
                                              :turn 16})
                        (assoc :turns (vec (repeat turns {})))))]
      (testing "a branch inside its grace period survives a dominating elder"
        (let [b (#'beam/cull-or-keep ctx (newborn 3) 2 [mature])]
          (is (state/active? b))
          (is (= 1 (count (filter #(= "cull-spared" (:kind %))
                                  (journal/events-since c rid 0)))))))
      (testing "past the grace period the ordinary rules resume"
        (is (= :culled (:status (#'beam/cull-or-keep
                                 ctx
                                 (newborn (inc (gates/threshold :juvenile-grace)))
                                 2 [mature])))))
      (testing "grace does not save a branch the critic calls a dead end"
        (let [doomed (assoc-in (newborn 3) [:critic :scores :viability] 1)]
          (is (= :culled (:status (#'beam/cull-or-keep ctx doomed 2 [])))))))))

(deftest pareto-retention-spares-non-dominated-branches
  ;; The scalar rule is the TRIGGER; domination is the verdict. Three runs in
  ;; a row culled a branch at 3 consecutive failures — including one whose
  ;; mathematics was right and whose Lean proofs merely kept failing. A
  ;; failing branch that no sibling dominates keeps exploring, on a clock.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          ctx {:conn c :run-id rid}
          failing (fn [fails scores]
                    (-> (branch-with :id "BF" :consecutive-failures fails
                                     :critic {:scores scores :turn 6})
                        (assoc :turns (vec (repeat 8 {})))))]
      (testing "failing and dominated: culled"
        (is (= :culled (:status (#'beam/cull-or-keep
                                 ctx
                                 (failing 3 {:progress 2 :momentum 2
                                             :distinctness 2 :viability 3})
                                 1
                                 [{:progress 3 :momentum 4
                                   :distinctness 3 :viability 4}])))))
      (testing "failing but non-dominated: spared, journaled, and told"
        (let [b (#'beam/cull-or-keep
                 ctx
                 (failing 3 {:progress 2 :momentum 2
                             :distinctness 5 :viability 3})
                 1
                 [{:progress 3 :momentum 4 :distinctness 2 :viability 4}])]
          (is (state/active? b))
          (is (some #(and (= "user" (:role %))
                          (str/includes? (:content %) "no sibling dominates"))
                    (:messages b)))
          (is (= 1 (count (filter #(= "cull-spared" (:kind %))
                                  (journal/events-since c rid 0)))))))
      (testing "the critic's own dead-end verdict culls"
        (is (= :culled (:status (#'beam/cull-or-keep
                                 ctx
                                 (failing 3 {:progress 2 :momentum 2
                                             :distinctness 5 :viability 1})
                                 1 [])))))
      (testing "the hard floor: double the threshold ends the reprieve"
        (is (= :culled (:status (#'beam/cull-or-keep
                                 ctx
                                 (failing 6 {:progress 2 :momentum 2
                                             :distinctness 5 :viability 5})
                                 1 []))))))))

(deftest the-beam-repopulates-when-it-falls-below-width
  ;; The blocker for a genuine frontier: culls remove width permanently and
  ;; the only route back up was a fork gated on a confirmation AND a
  ;; cooldown, so every run in the campaign decayed toward one line — five
  ;; runs, five collapses to a single branch. A population that only ever
  ;; shrinks is not a frontier. When the beam drops below its target width
  ;; and the cap allows, the strongest survivor is told to reseed it.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          ctx {:conn c :run-id rid :beam-width 3}
          scored (fn [id sc turns]
                   (-> (branch-with :id id :critic {:scores sc :turn 6})
                       (assoc :turns (vec (repeat turns {})))))
          strong (scored "B1" {:progress 4 :momentum 4 :distinctness 3 :viability 5} 12)
          weak (scored "B2" {:progress 1 :momentum 2 :distinctness 2 :viability 2} 12)
          dead (assoc (scored "B3" {:progress 1 :momentum 1 :distinctness 1 :viability 1} 12)
                      :status :culled)]
      (testing "below target width, the strongest survivor is marked to reseed"
        (let [bs (#'beam/repopulate ctx [strong weak dead] 3 20)
              b1 (first (filter #(= "B1" (:id %)) bs))
              b2 (first (filter #(= "B2" (:id %)) bs))]
          (is (= 20 (:fork-invited b1)))
          ;; The mark, not a message: the ask itself is the :repopulate gate,
          ;; so it carries a prediction and settles. See
          ;; repopulation-is-a-gate-with-a-prediction-not-an-invitation.
          (is (= 20 (:repopulate-due b1)))
          (is (= (count (:messages strong)) (count (:messages b1)))
              "the scheduler does not speak on a boundary the arbiter owns")
          (is (nil? (:fork-invited b2)) "one ask, to the strongest")
          (is (= 1 (count (filter #(= "repopulate" (:kind %))
                                  (journal/events-since c rid 0)))))))
      (testing "at or above target width it stays quiet"
        (is (nil? (:fork-invited
                   (first (#'beam/repopulate ctx [strong weak
                                                  (scored "B4" {:progress 2 :momentum 2
                                                                :distinctness 2 :viability 2} 12)]
                                             3 20))))))
      (testing "no room under the cap, no ask"
        (is (nil? (:fork-invited
                   (first (#'beam/repopulate ctx [strong dead]
                                             (gates/threshold :max-total-branches) 20))))))
      (testing "a recent ask is not repeated"
        (is (= 18 (:fork-invited
                   (first (#'beam/repopulate ctx [(assoc strong :fork-invited 18) dead]
                                             3 20)))))))))

(deftest every-gate-renders-its-message
  ;; The progress-stalled gate referenced resources/prompts/progress-stalled.md,
  ;; which did not exist. `slurp` of a nil resource throws, so the gate killed
  ;; whatever branch it fired on, and because a dying branch is abandoned
  ;; rather than surfaced the gate simply never appeared in any tally. It had
  ;; never worked. Rendering every gate's message is the general guard: a gate
  ;; that cannot produce its text is not a quiet gate, it is a broken one.
  (let [ctx {:branch (branch-with :turns (vec (repeat 5 {}))
                                  :turns-since-progress 4
                                  :green-at-turn 2)
             :max-turns 40
             :done-block "blocked because ..."
             :directive {:payload "a human said so"}
             :safe-state-coverage {:ok true}}]
    (doseq [g gates/gates]
      (testing (str (:gate g))
        (let [msg ((:message g) ctx)]
          (is (string? msg))
          (is (not (str/blank? msg))
              (str (:gate g) " produced an empty message")))
        (is (string? ((:prediction g) ctx)))))))

;; --- what the judge says back to the branch ---------------------------------

(deftest judge-reasoning-never-reaches-the-branch
  ;; A review or audit result is appended to the branch's message history, so
  ;; whatever it contains is in context for every later turn. When the judge's
  ;; <think> block went in verbatim, the branch's next model call read
  ;; reviewer-voice reasoning and imitated it: it answered as a reviewer, in
  ;; prose, with no tool call. Nine of twenty turns in a Lean run were lost that
  ;; way, and each one also carried the full reasoning stream forward.
  (let [raw (str "<think>We need answer as reviewer. Need decide pass/fail. "
                 "Maybe FAIL. Actually the encodings differ.</think>\n"
                 "PASS — the cross-check uses a different formulation.\n\nVERDICT: PASS")
        clean (verdict/strip-reasoning raw)]
    (is (not (str/includes? clean "<think>")))
    (is (not (str/includes? clean "Need decide pass/fail")))
    (is (str/includes? clean "different formulation")
        "the judge's actual justification must survive; it is why the branch is being told")))

(deftest strip-reasoning-handles-an-unclosed-block
  ;; A judge that hits the token cap mid-thought emits <think> with no closing
  ;; tag. Leaving that in would put the whole truncated stream into context.
  (is (= "" (verdict/strip-reasoning "<think>thinking and then the cap hit")))
  (is (= "answer" (verdict/strip-reasoning "<think>a</think>\nanswer")))
  (is (= "plain" (verdict/strip-reasoning "plain"))))

(deftest provenance-words-are-not-treated-as-claims
  ;; The gate is aimed at fabricated specifics: a number or a name in the
  ;; answer that no artifact supports. Words describing HOW something was
  ;; checked can never appear in an artifact, because an artifact's claim and
  ;; code are about the problem. Flagging them refused a correct answer three
  ;; times in one run and pushed the model toward stripping its explanation.
  (let [artifacts [{:claim "for every n, sum of first n odds = n^2"
                    :code "theorem t (n : Nat) : ..." :witness nil}]]
    (is (empty? (tools/uncovered-tokens
                 "For every n, the sum of the first n odd numbers equals n^2. This
                  universal statement is kernel-checked by two Lean 4 + Mathlib
                  theorems, proved by induction on the successor."
                 artifacts))
        "provenance prose must not be read as unsupported assertion"))

  (testing "the guard still catches a fabricated number"
    (is (= ["24"] (tools/uncovered-tokens "the answer is 24"
                                          [{:claim "the answer is 23" :code "" :witness nil}])))))

(deftest a-tool-version-is-not-a-numeric-claim
  ;; "Lean 4" asserts the number 4 under a naive tokenizer, and numbers are the
  ;; part of this gate that must not be relaxed. Stripped as a name-plus-version
  ;; phrase rather than by exempting the digit.
  (is (empty? (tools/answer-tokens "verified with Lean 4 and Mathlib")))
  (is (= ["4"] (tools/answer-tokens "the answer is 4"))
      "a bare number is still a claim"))

;; --- consensus: judge-not-vote ----------------------------------------------

(deftest single-report-passes-through-without-a-judge
  ;; One verdict is not an aggregation. No judge call, no augmentation: the
  ;; report is the answer, and the disagreement keys stay absent.
  (let [report {:verdict :pass :gaps nil :text "verified" :source "review"}
        called? (atom false)
        judge (fn [& _] (reset! called? true) {:verdict :fail})]
    (is (= report (consensus/judge-reports [report] judge)))
    (is (= report (consensus/judge-reports [report]))
        "no judge fn is fine with a single report")
    (is (false? @called?) "the judge is never invoked for one report")))

(deftest agreeing-reports-still-go-through-the-judge
  ;; Agreement is not an auto-pass. Two PASS reports with identical gap lists
  ;; are aggregated like anything else, and the judge may still reject the
  ;; claim — the agreement is evidence the judge weighs, not a decision.
  (let [r1 {:verdict :pass :gaps nil :text "a" :source "A"}
        r2 {:verdict :pass :gaps nil :text "b" :source "B"}
        seen (atom nil)
        judge (fn [reports disagreements]
                (reset! seen [reports disagreements])
                {:verdict :fail :text "both encodings share the culled assumption"})]
    (let [result (consensus/judge-reports [r1 r2] judge)]
      (is (some? @seen) "two agreeing reports still invoke the judge")
      (is (= :fail (:verdict result)) "the judge's rejection stands over agreement")
      (is (empty? (:disagreements result)) "no report is party to a disagreement"))))

(deftest conflicting-reports-hand-the-disagreement-set-to-the-judge
  (let [r1 {:verdict :pass :gaps nil :text "a" :source "A"}
        r2 {:verdict :fail :gaps ["the witness leaves X unbound"]
            :text "b" :source "B"}
        seen (atom nil)
        judge (fn [reports disagreements]
                (reset! seen [reports disagreements])
                {:verdict :pass :text "the gap is addressed elsewhere"})]
    (let [result (consensus/judge-reports [r1 r2] judge)]
      (is (= :pass (:verdict result)) "the judge's answer wins the 1-1 split")
      (is (= #{r1 r2} (:disagreements result)) "both reports are party to the conflict")
      (is (= #{r1 r2} (second @seen)) "the disagreement set reaches the judge")
      (is (= "the gap is addressed elsewhere" (:reasoning result))
          "the judge's reasoning is carried on the result"))))

(deftest aggregating-two-reports-without-a-judge-throws
  ;; Counting verdicts is the vote. With two or more reports the judge fn is
  ;; required, so the majority-count path is not merely discouraged: it is
  ;; unrepresentable.
  (is (thrown? clojure.lang.ExceptionInfo
               (consensus/judge-reports [{:verdict :pass :source "A"}
                                         {:verdict :fail :source "B"}])))
  (is (thrown? clojure.lang.ExceptionInfo
               (consensus/judge-reports [{:verdict :pass :source "A"}
                                         {:verdict :fail :source "B"}] nil))))

(deftest the-judge-siding-with-the-one-beats-the-three
  ;; The anti-vote property, stated as a name: 3 reports say PASS, 1 says FAIL,
  ;; the judge reads the evidence and sides with the 1. Majority counting would
  ;; hand the claim PASS; the contract hands the claim to the judge.
  (let [pass {:verdict :pass :gaps nil :text "looks fine" :source "P"}
        fail {:verdict :fail :gaps ["counterexample at x=3"]
              :text "found one" :source "F"}
        judge (fn [& _]
                {:verdict :fail
                 :text "the located counterexample refutes the claim"})]
    (let [result (consensus/judge-reports [pass pass pass fail] judge)]
      (is (= :fail (:verdict result))
          "the judge's verdict wins over a 3-1 vote")
      (is (= #{pass fail} (:disagreements result))
          "every report in a split is party to the disagreement"))))

(deftest engine-agreement-counts-distinct-engines-not-artifact-rows
  ;; Counting is legitimate only for independent engine confirmations. Two Z3
  ;; artifacts for the same claim are one confirmation, not two; Z3 plus Prolog
  ;; is two. Claim grouping is spelling-normalized.
  (let [artifacts [{:claim "the sidon set exists" :kind :smt :tier :fast}
                   {:claim "the sidon set exists" :kind :smt :tier :slow}
                   {:claim "The Sidon set exists" :kind :prolog :tier :slow}
                   {:claim "there is no 3-coloring" :kind :prolog :tier :slow}]]
    (let [agreement (consensus/engine-agreement artifacts)]
      (is (= 2 (get agreement "the sidon set exists"))
          "SMT and Prolog confirm the claim: 2 distinct kinds")
      (is (= 1 (get agreement "there is no 3 coloring"))
          "one engine kind counts once, no matter how many artifacts")
      (is (= 2 (count agreement)) "claims are grouped by normalized text"))))

(deftest consensus-judgement-is-journalled-when-conn-and-run-id-are-supplied
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        r1 {:verdict :pass :gaps nil :text "a" :source "A"}
        r2 {:verdict :fail :gaps ["the witness leaves X unbound"]
            :text "b" :source "B"}
        judge (fn [& _]
                {:verdict :pass
                 :text "A is right: the unbound X is bound by the witness"})]
    (consensus/judge-reports [r1 r2] judge c rid)
    (let [evs (filter #(= "consensus-judgement" (:kind %))
                      (journal/events-since c rid 0))
          ev (first evs)]
      (is (= 1 (count evs)) "one judgement event per aggregation")
      (is (str/includes? (:data ev) "\"disagreements\""))
      (is (str/includes? (:data ev) "\"reasoning\""))
      (is (str/includes? (:data ev)
                         "A is right: the unbound X is bound by the witness")
          "the judge's reasoning is journalled")
      (is (str/includes? (:data ev) "\"B\"") "the disagreeing report is named"))
    ;; nil conn skips the journal silently — the guard idiom from tools.clj.
    (is (= :pass (:verdict (consensus/judge-reports [r1 r2] judge nil nil))))))

;; --- done-eligible ranking ---------------------------------------------------

(defn- finished-branch
  "A minimal done-eligible branch: :final-answer plus whatever evidence the
  test under exercise needs. Explicit per test, so each one states only the
  axes it is about."
  [id & {:as evidence}]
  (merge (state/new-branch {:id id :problem "p"})
         {:final-answer (str "answer " id)}
         evidence))

(deftest rank-finished-non-relaxation-beats-relaxation
  (testing "a direct proof outranks a relaxation even when the relaxation carries more artifacts"
    (let [direct (finished-branch "B1"
                                  :last-audit {:relaxation? false}
                                  :artifacts [{:kind :prolog :claim "every element of S satisfies P"
                                               :claim-status :confirmed :tier :fast}])
          relaxed (finished-branch "B2"
                                   :last-audit {:relaxation? true}
                                   :artifacts [{:kind :prolog :claim "every element of S satisfies P"
                                                :claim-status :confirmed :tier :fast}
                                               {:kind :smt :claim "every element of T satisfies Q"
                                                :claim-status :confirmed :tier :fast}
                                               {:kind :smt :claim "c3"
                                                :claim-status :confirmed :tier :fast}])]
      (is (= ["B1" "B2"] (mapv :id (state/rank-finished [relaxed direct])))
          "non-relaxation is the first component, so it wins regardless of the rest"))))

(deftest rank-finished-slow-tier-beats-fast-only
  (testing "any slow-tier evidence outranks a fast-only branch with more artifacts"
    (let [fast (finished-branch "B1"
                                :artifacts [{:kind :prolog :claim "every element of S satisfies P"
                                             :claim-status :confirmed :tier :fast}
                                            {:kind :smt :claim "every element of T satisfies Q"
                                             :claim-status :confirmed :tier :fast}])
          slow (finished-branch "B2"
                                :tiers-seen #{:slow}
                                :artifacts [{:kind :prolog :claim "every element of S satisfies P"
                                             :claim-status :confirmed :tier :fast}])]
      (is (= ["B2" "B1"] (mapv :id (state/rank-finished [fast slow])))
          "the review/template signal is compared before artifact count"))))

(deftest rank-finished-engine-diversity-beats-count
  (testing "distinct engine kinds compare before artifact count"
    (let [two-smt (finished-branch "B1"
                                   :artifacts [{:kind :smt :claim "every element of S satisfies P"
                                                :claim-status :confirmed :tier :fast}
                                               {:kind :smt :claim "every element of T satisfies Q"
                                                :claim-status :confirmed :tier :fast}])
          smt-plus-prolog (finished-branch "B2"
                                           :artifacts [{:kind :smt :claim "every element of S satisfies P"
                                                        :claim-status :confirmed :tier :fast}
                                                       {:kind :prolog :claim "every element of S satisfies P"
                                                        :claim-status :confirmed :tier :fast}])]
      (is (= ["B2" "B1"] (mapv :id (state/rank-finished [two-smt smt-plus-prolog])))
          "one z3 + one prolog beats two z3s: diversity (component c) outranks count (component d)"))))

(deftest rank-finished-id-breaks-ties-stably
  (testing "identical evidence ranks by branch id ascending, independent of input order"
    (let [mk (fn [id] (finished-branch id
                                       :artifacts [{:kind :prolog :claim "every element of S satisfies P"
                                                    :claim-status :confirmed :tier :fast}]))
          expect ["B10" "B2" "B7"]]
      (is (= expect (mapv :id (state/rank-finished [(mk "B7") (mk "B10") (mk "B2")]))))
      (is (= expect (mapv :id (state/rank-finished [(mk "B2") (mk "B7") (mk "B10")])))))))

(deftest candidate-selection-is-journalled-when-multiple-finish
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          winner (beam/select-done-branch
                  {:conn c :run-id rid}
                  [(finished-branch "B1"
                                    :artifacts [{:kind :prolog :claim "every element of S satisfies P"
                                                 :claim-status :confirmed :tier :fast}])
                   (finished-branch "B2"
                                    :artifacts [{:kind :prolog :claim "every element of S satisfies P"
                                                 :claim-status :confirmed :tier :fast}
                                                {:kind :smt :claim "every element of T satisfies Q"
                                                 :claim-status :confirmed :tier :fast}])])
          evs (filter #(= "candidate-selection" (:kind %))
                      (journal/events-since c rid 0))
          data (json/read-str (:data (first evs)) :key-fn keyword)]
      (is (= "B2" (:id winner)) "the more diverse branch wins")
      (is (= 1 (count evs)) "one note per selection")
      (is (= "B2" (:winner data)))
      (is (= #{"B1" "B2"} (set (map :branch-id (:candidates data)))))
      (is (= [1 0 1 1 "B1"] (:key (first (:candidates data))))
          "the journal records each candidate's ranking inputs"))))

(deftest candidate-selection-is-silent-for-a-lone-finisher
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          winner (beam/select-done-branch
                  {:conn c :run-id rid}
                  [(finished-branch "B1"
                                    :artifacts [{:kind :prolog :claim "every element of S satisfies P"
                                                 :claim-status :confirmed :tier :fast}])])]
      (is (= "B1" (:id winner)))
      (is (empty? (filter #(= "candidate-selection" (:kind %))
                          (journal/events-since c rid 0)))
          "no note when there was nothing to choose between"))))

;; --- resume ------------------------------------------------------------------

(deftest resumability-is-a-one-way-door
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 10 :beam-width 1})]
      (is (resume/resumable? c rid) "a running run resumes")
      (runs/finish-run! c rid :completed "42")
      (is (not (resume/resumable? c rid)) "a completed run shipped; the answer is the record"))
    (let [rid (runs/start-run! c {:problem "p" :max-turns 10 :beam-width 1})]
      (runs/finish-run! c rid :aborted nil)
      (is (not (resume/resumable? c rid)) "abort is a person saying stop; resume is not them changing their mind"))
    (let [rid (runs/start-run! c {:problem "p" :max-turns 10 :beam-width 1})]
      (runs/finish-run! c rid :failed nil)
      (is (resume/resumable? c rid) "an exhausted process that never tore down may continue"))
    (is (not (resume/resumable? c "no-such-run")))))

(deftest resume-with-extended-budget-reopens-exhausted-branches
  ;; vf-huj: exhaustion is terminal today, and the original-budget anchor
  ;; exists so a CRASH cannot re-grant turns. A human explicitly extending
  ;; the budget is a different act: the runs row is updated, branches closed
  ;; as `exhausted` reopen — culled branches died for cause and stay closed —
  ;; and the extension is journaled.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 2 :beam-width 2})]
      (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
      (runs/open-branch! c rid {:branch-id "B2" :created-at-turn 0})
      (journal/record-turn! c rid {:branch-id "B1" :turn 1 :tool-name "verify"
                                   :result "ok" :category "success"})
      (journal/record-turn! c rid {:branch-id "B1" :turn 2 :tool-name "verify"
                                   :result "ok" :category "success"})
      (runs/close-branch! c rid "B1" :exhausted "turn cap of 2 reached")
      (runs/close-branch! c rid "B2" :culled "culled after 3 consecutive failures")
      (runs/finish-run! c rid :failed nil)
      (let [handed (atom nil)]
        (with-redefs [beam/run-rounds (fn [ctx branches start-turn]
                                        (reset! handed {:ctx ctx :branches branches
                                                        :start-turn start-turn})
                                        {:status :captured})
                      veriframe.engine.prolog/create-session (fn [_] nil)]
          (resume/resume! {:conn c :config {} :run-id rid :max-turns 6}))
        (let [{:keys [ctx branches start-turn]} @handed
              by-id (into {} (map (juxt :id identity) branches))]
          (is (= 3 start-turn))
          (is (= 6 (:max-turns ctx)) "the extended budget reaches the loop")
          (is (= :active (:status (by-id "B1"))) "the exhausted branch reopens")
          (is (= :culled (:status (by-id "B2"))) "culled stays closed")
          (is (= 6 (:max_turns (runs/get-run c rid)))
              "the runs row records the new budget")
          (is (= "active" (:status (first (filter #(= "B1" (:id %))
                                                  (runs/branches c rid)))))
              "the branches row reopens, so a crash mid-extension replays right")
          (is (= 1 (count (filter #(= "budget-extended" (:kind %))
                                  (journal/events-since c rid 0))))))))))

(deftest resume-without-extension-keeps-exhausted-branches-closed
  ;; The anchor rule stands when no extension is asked for.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 2 :beam-width 1})]
      (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
      (journal/record-turn! c rid {:branch-id "B1" :turn 2 :tool-name "verify"
                                   :result "ok" :category "success"})
      (runs/close-branch! c rid "B1" :exhausted "turn cap of 2 reached")
      (runs/finish-run! c rid :failed nil)
      (let [handed (atom nil)]
        (with-redefs [beam/run-rounds (fn [_ branches _]
                                        (reset! handed branches)
                                        {:status :captured})
                      veriframe.engine.prolog/create-session (fn [_] nil)]
          (resume/resume! {:conn c :config {} :run-id rid}))
        (is (= :exhausted (:status (first @handed)))
            "no override, no reopening")
        (is (= 2 (:max_turns (runs/get-run c rid))))))))

(deftest seed-from-run-imports-confirmed-artifacts
  ;; vf-b1f: cross-run campaigns. A prior run's engine-confirmed artifacts —
  ;; claim AND verification code — enter the new run's shared log under a
  ;; seed: branch id, so no live branch's own-branch exclusion hides them.
  ;; Refuted and existential artifacts stay behind.
  (with-db [c]
    (let [old (runs/start-run! c {:problem "p"})
          new (runs/start-run! c {:problem "p2"})]
      (journal/record-artifact! c old {:branch-id "B1" :turn 3 :kind :smt
                                       :claim "no covering with moduli 3 5 7 9 exists"
                                       :code "(check-sat)"
                                       :claim-status :confirmed :tier :fast})
      (journal/record-artifact! c old {:branch-id "B2" :turn 4 :kind :prolog
                                       :claim "a refuted claim about covering"
                                       :code "x" :claim-status :refuted :tier :fast})
      (is (= 1 (artifacts/seed-from-run! c new old)))
      (let [rows (artifacts/recent c new)]
        (is (= 1 (count rows)) "only confirmed artifacts cross over")
        (is (= "seed:B1" (:branch_id (first rows))))
        (is (= "(check-sat)" (:code (first rows)))
            "the verification code rides along for cheap re-confirmation"))
      (testing "a branch named like the source branch still sees the seed"
        (let [{:keys [block]} (#'aloop/context-block c new (branch-with :id "B1")
                                                     "covering moduli" true)]
          (is (str/includes? block "no covering with moduli"))))
      (is (= 1 (count (filter #(= "run-seeded" (:kind %))
                              (journal/events-since c new 0))))))))

(deftest beam-run-seeds-and-forces-sharing
  ;; The wiring: run! with :seed-run imports before any branch takes a turn,
  ;; and seeding forces share-artifacts? on for the run — seeds nobody reads
  ;; would be dead rows.
  (with-db [c]
    (let [old (runs/start-run! c {:problem "p"})]
      (journal/record-artifact! c old {:branch-id "B1" :turn 1 :kind :smt
                                       :claim "an inherited lemma" :code "(y)"
                                       :claim-status :confirmed :tier :fast})
      (let [handed (atom nil)]
        (with-redefs [beam/run-rounds (fn [ctx branches _]
                                        (reset! handed {:ctx ctx :branches branches})
                                        {:status :captured :run-id (:run-id ctx)})
                      veriframe.engine.prolog/create-session (fn [_] nil)]
          (beam/run! {:conn c :config {:run {:share-artifacts? false}}
                      :llm-config {:provider :local :model "m"}
                      :problem "p2" :max-turns 5 :beam-width 1
                      :seed-run old}))
        (let [{:keys [ctx]} @handed
              new (:run-id ctx)]
          (is (true? (get-in ctx [:config :run :share-artifacts?]))
              "seeding forces sharing on")
          (is (= 1 (count (artifacts/recent c new)))
              "the seed landed before the first turn"))))))

(deftest resume-replays-the-journal-under-the-original-budget
  ;; The anchor rule: a run recorded through turn 3 of 10 gets 7 more turns,
  ;; not 10. run-rounds is stubbed to capture what resume hands it, so the
  ;; test is offline - no model, no swipl.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "prove P" :max-turns 10 :beam-width 1})]
      (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
      (runs/set-thesis! c rid "B1" {:goal "prove P" :technique "t" :subClaims ["lemma A"]})
      (journal/record-turn! c rid {:branch-id "B1" :turn 1 :tool-name "verify"
                                   :result "confirmed" :category "success"
                                   :assistant-text "trying lemma A"})
      (journal/record-artifact! c rid {:branch-id "B1" :turn 1 :kind :smt
                                       :claim "lemma A" :code "(x)"
                                       :claim-status :confirmed :tier :fast})
      (journal/record-turn! c rid {:branch-id "B1" :turn 2 :tool-name "verify"
                                   :result "goal failed" :category "failure"})
      (journal/record-turn! c rid {:branch-id "B1" :turn 3 :tool-name "verify"
                                   :result "goal failed" :category "failure"})
      (journal/record-gate! c rid {:branch-id "B1" :turn 3 :gate "stuck"
                                   :prediction "changes technique" :window 3})
      (let [handed (atom nil)]
        (with-redefs [beam/run-rounds (fn [ctx branches start-turn]
                                        (reset! handed {:ctx ctx :branches branches
                                                        :start-turn start-turn})
                                        {:status :captured})
                      veriframe.engine.prolog/create-session (fn [_] nil)]
          (is (= :captured (:status (resume/resume! {:conn c :config {} :run-id rid})))))
        (let [{:keys [ctx branches start-turn]} @handed
              b (first branches)]
          (is (= 4 start-turn) "continues one past the last recorded turn")
          (is (= 10 (:max-turns ctx)) "under the ORIGINAL budget, not a fresh one")
          (is (= "B1" (:id b)))
          (is (= 1 (count (state/confirmed-artifacts b))))
          (is (= "prove P" (get-in b [:thesis :goal])))
          (is (= 2 (:consecutive-failures b)) "counters recomputed from the turns table")
          (is (:any-progress? b))
          (is (= 1 (count (:open-predictions b))) "the unsettled firing still settles later")
          (is (some #(= "trying lemma A" (:content %)) (:messages b))
              "the model's own words are back in its history"))))))

;; --- a crashing run must say so ---------------------------------------------

(deftest a-beam-that-throws-marks-the-run-failed-and-journals-the-error
  ;; gen-11 died mid-round and sat at status 'running' with ended_at NULL for
  ;; the rest of the night. The exception went to the process's stdout — a tty
  ;; — and nowhere else, so a crashed run and a healthy slow round were
  ;; indistinguishable from the journal, the API and the GUI alike.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)]
    (with-redefs [veriframe.engine.prolog/create-session (fn [_] nil)
                  interventions/pending (fn [& _]
                                          (throw (ex-info "boom in round 1" {})))]
      (is (thrown? Throwable
                   (beam/run! {:conn c :config {:run {:share-artifacts? false}}
                               :problem "p" :beam-width 1 :max-turns 5}))
          "the throw still reaches the caller rather than being swallowed here"))
    (let [r (first (runs/list-runs c))
          rid (:id r)
          errs (filter #(= "run-error" (:kind %)) (journal/events-since c rid 0))]
      (is (= "failed" (:status r)) "the run row records the crash")
      (is (some? (:ended_at r)) "and is closed out rather than left open")
      (is (= 1 (count errs)) "exactly one run-error entry")
      (is (str/includes? (:data (first errs)) "boom in round 1")
          "carrying the message, so the journal explains the death")
      ;; jolt's Throwable carries an empty .getStackTrace, so the type and the
      ;; ex-data are the whole of what can be preserved. Recorded anyway: the
      ;; terminal is not a durable log, and "which exception" narrows a hunt
      ;; that otherwise starts from nothing.
      (is (str/includes? (:data (first errs)) "ExceptionInfo")
          "and the exception type"))))

;; --- an encoding must actually encode the claim -----------------------------

(defn- smt-artifact-status
  "Run verify_smt with Z3 stubbed to `verdict` and the faithfulness judge
  stubbed to `judge-reply`, and return the resulting claim-status."
  ([verdict judge-reply claim] (smt-artifact-status verdict judge-reply claim nil))
  ([verdict judge-reply claim args]
   (let [c (db/connect ":memory:")
         _ (db/migrate! c)
         rid (runs/start-run! c {:problem "p" :beam-width 1})
         b (state/new-branch {:id "B1" :problem "p"})]
     (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
     (with-redefs [smt/run-smt (fn [& _] {:status :ok :verdict verdict})
                   llm/chat (fn [& _] {:content judge-reply})]
       (-> (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                            :tool-name "verify_smt"
                            :args (merge {:claim claim
                                          :smtlib "(assert (>= (* 5 y) 1))(check-sat)"
                                          :expectedVerdict "unsat"}
                                         args)})
           :artifact :claim-status)))))

(deftest an-smt-encoding-that-does-not-match-its-claim-is-not-confirmed
  ;; Three gen-11 artifacts shipped as confirmed while encoding a condition
  ;; strictly stronger than the claim: moduli divisible by 3 were given
  ;; coefficient L/m where the stated mod-3 layered condition requires 3L/m.
  ;; Z3's unsat was real and the claim was false, because nothing compared the
  ;; SMT-LIB to the English. expectedVerdict cannot catch this — it was correct.
  (is (= :unfaithful
         (smt-artifact-status :unsat
                              "GAP: 3-divisible moduli carry L/m, not 3L/m\nVERDICT: FAIL"
                              "No subcollection satisfies the mod-3 layered condition"))
      "a judge that rejects the encoding blocks the confirmation")
  (is (= :confirmed
         (smt-artifact-status :unsat "GAPS: none\nVERDICT: PASS"
                              "No subcollection satisfies the mod-3 layered condition"))
      "and an encoding it accepts still confirms"))

(deftest the-faithfulness-check-fails-closed-and-costs-nothing-when-there-is-no-assertion
  (is (= :unfaithful
         (smt-artifact-status :unsat "the judge rambled without a verdict line"
                              "some claim"))
      "an unreadable judge leaves the gate shut rather than defaulting open")
  ;; An ambiguous outcome asserts nothing — there is no claim and no negation
  ;; to be unfaithful to — so the judge is never called. A stubbed llm/chat
  ;; that throws proves it.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (state/new-branch {:id "B1" :problem "p"})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [smt/run-smt (fn [& _] {:status :ok :verdict :sat})
                  llm/chat (fn [& _] (throw (ex-info "judge must not be called" {})))]
      (is (= :ambiguous
             (-> (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                  :tool-name "verify_smt"
                                  :args {:claim "every element of S satisfies P" :smtlib "(check-sat)"}})
                 :artifact :claim-status))
          "no expectedVerdict means no assertion, so nothing to review"))))

(deftest a-refutation-is-reviewed-like-a-confirmation
  ;; It used to skip the judge, on the reasoning that a refuted artifact
  ;; substantiates nothing. It substantiates the NEGATION, which the branch
  ;; believes, the claims registry hands to other branches as settled, and
  ;; nobody re-runs. gen-13 refuted "the mod-15 condition for P_3000 is
  ;; satisfiable" from an encoding that pinned y0 = 0 — the claim quantified
  ;; over every y0, so the unsat was about a different question.
  (is (= :unfaithful
         (smt-artifact-status :unsat "GAP: the encoding pins y0\nVERDICT: FAIL"
                              "some claim" {:expectedVerdict "sat"}))
      "an encoding review rejects blocks the refutation too")
  (is (= :refuted
         (smt-artifact-status :unsat "GAPS: none\nVERDICT: PASS"
                              "some claim" {:expectedVerdict "sat"}))
      "and one it accepts still refutes"))

;; --- forking twice must not collide -----------------------------------------

(deftest child-ids-skip-suffixes-the-parent-already-used
  ;; Killed gen-11 and gen-12. Child ids were parent + "." + (batch index + 2),
  ;; which has no memory of an earlier fork, so a branch that forked twice
  ;; reissued its first child's id and the INSERT hit
  ;; `UNIQUE constraint failed: branches.run_id, branches.id`, taking the run
  ;; down. Repopulation makes this the common case rather than a corner: it
  ;; asks the strongest survivor to branch again, and the strongest survivor is
  ;; the one that has already branched.
  (is (= ["B2.2" "B2.3"] (#'beam/child-ids #{} "B2" 2))
      "a first fork still starts at .2")
  (is (= ["B2.4" "B2.5"] (#'beam/child-ids #{"B2.2" "B2.3"} "B2" 2))
      "a second fork continues past the children already spawned")
  (is (= ["B2.3" "B2.5"] (#'beam/child-ids #{"B2.2" "B2.4"} "B2" 2))
      "gaps are reusable: ids are unique keys, not a spawn ordering")
  (is (= ["B1.2.2"] (#'beam/child-ids #{"B1.2"} "B1.2" 1))
      "the parent's own id is not a child id and must not be skipped over"))

(deftest a-parent-can-fork-twice-without-taking-the-run-down
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})]
    (with-redefs [veriframe.engine.prolog/create-session (fn [_] nil)]
      (let [ctx {:conn c :run-id rid :config {} :problem "p" :sessions (atom [])}
            thesis {:goal "g" :technique "t" :subClaims []}
            parent (assoc (state/new-branch {:id "B2" :problem "p"})
                          :pending-branch-theses [thesis thesis])]
        (runs/open-branch! c rid {:branch-id "B2" :created-at-turn 0})
        (let [[kids1 p1] (#'beam/spawn-children! ctx parent 1 5)
              p2 (assoc p1 :pending-branch-theses [thesis])
              [kids2 _] (#'beam/spawn-children! ctx p2 3 9)
              ids (mapv :id (concat kids1 kids2))]
          (is (= ["B2.2" "B2.3"] (mapv :id kids1)))
          (is (= ["B2.4"] (mapv :id kids2))
              "the second fork does not reissue B2.2")
          (is (= (count ids) (count (distinct ids))))
          (is (= (set (conj ids "B2"))
                 (set (map :id (runs/branches c rid))))
              "and every child reached the branches table"))))))

;; --- a prolog artifact must be auditable and mean what it claims ------------

(defn- prolog-verify
  "Run the prolog `verify` tool with the engine and judge stubbed."
  [{:keys [answers rules judge-reply claim check]}]
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (assoc (state/new-branch {:id "B1" :problem "p"}) :prolog ::session)]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [veriframe.engine.prolog/query (fn [& _] {:ok true :answers answers})
                  veriframe.engine.prolog/snapshot (fn [_] rules)
                  llm/chat (fn [& _] {:content judge-reply})]
      (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                       :tool-name "verify"
                       :args {:claim claim :check check}}))))

(deftest a-prolog-artifact-carries-the-rules-its-goal-depends-on
  ;; gen-13 a#333 recorded its code as the bare goal `sat_105`. The rule that
  ;; gave that goal meaning lived in an earlier add_rule turn, so the artifact
  ;; could not be audited from the artifacts table by a judge or by a person —
  ;; and the rule turned out to post its constraints inside findall/3, which
  ;; discards them, making the confirmed claim false.
  (let [r (prolog-verify {:answers [{:bindings {} :formatted "true"}]
                          :rules [{:name "q105" :code "sat_105 :- member(X,[1]), X > 0."}]
                          :judge-reply "GAPS: none\nVERDICT: PASS"
                          :claim "the q=105 condition is satisfiable"
                          :check "sat_105"})
        code (get-in r [:artifact :code])]
    (is (str/includes? code "sat_105 :- member(X,[1]), X > 0.")
        "the rule body travels with the artifact")
    (is (str/includes? code "sat_105")
        "and so does the goal that was actually run")))

(deftest a-ground-prolog-goal-still-faces-the-faithfulness-check
  ;; A goal with no variables reports {} bindings, which is neither "bound" nor
  ;; the all-unbound case the existential guard catches, so it went straight to
  ;; :confirmed on the strength of having succeeded at all.
  (is (= :unfaithful
         (-> (prolog-verify {:answers [{:bindings {} :formatted "true"}]
                             :rules [{:code "sat_105 :- true."}]
                             :judge-reply (str "GAP: the goal never constrains the class"
                                               " densities\nVERDICT: FAIL")
                             :claim "the q=105 condition is satisfiable"
                             :check "sat_105"})
             :artifact :claim-status))
      "a judge that rejects the rules blocks the confirmation")
  (is (= :confirmed
         (-> (prolog-verify {:answers [{:bindings {} :formatted "true"}]
                             :rules [{:code "sat_105 :- true."}]
                             :judge-reply "GAPS: none\nVERDICT: PASS"
                             :claim "the q=105 condition is satisfiable"
                             :check "sat_105"})
             :artifact :claim-status))
      "and one it accepts still confirms"))

(deftest an-all-unbound-prolog-goal-never-reaches-the-judge
  ;; It is already :existential and substantiates nothing, so there is no
  ;; confirmation to guard and no reason to pay for a model call.
  (is (= :existential
         (-> (prolog-verify {:answers [{:bindings {:A "_G123"} :formatted "A = _G123"}]
                             :rules [{:code "r :- true."}]
                             :judge-reply "irrelevant, must not be called"
                             :claim "every element of S satisfies P" :check "r"})
             :artifact :claim-status))))

;; --- a rejection has to carry the reviewer's actual objection ---------------

(deftest a-rejection-quotes-the-objection-to-the-branch-and-the-failure-log
  ;; Watched this burn turns live. The reviewer found a sign error in one
  ;; divergence equation and named the fix exactly — change `(+ (- k4) (- k7))`
  ;; to `(+ k4 (- k7))`. The branch was shown "Check every coefficient, bound
  ;; and index set against the claim's wording", which was all the tool ever
  ;; said, and resubmitted the identical artifact on the next turn.
  ;;
  ;; The same boilerplate went into the failure log, which IS cross-branch, so
  ;; every other branch inherited "z3 returned unsat (status unfaithful)" and
  ;; learned nothing either. encoding-faithful? returned a bare boolean and
  ;; threw the reasoning away.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (state/new-branch {:id "B1" :problem "p"})
        objection "GAP: the vertex-6 divergence equation has the wrong sign.\nVERDICT: FAIL"]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [smt/run-smt (fn [& _] {:status :ok :verdict :unsat})
                  llm/chat (fn [& _] {:content objection})]
      (let [r (tools/run-tool {:branch b :turn 3 :conn c :run-id rid
                               :tool-name "verify_smt"
                               :args {:claim "every element of S satisfies P"
                                      :smtlib "(assert (>= x 1))(check-sat)"
                                      :expectedVerdict "unsat"}})]
        (is (= :unfaithful (get-in r [:artifact :claim-status])))
        (is (str/includes? (:result r) "vertex-6 divergence equation")
            "the branch is told what was actually wrong, not to go look again")
        (is (str/includes? (str (get-in r [:failure :reason])) "vertex-6")
            "and the failure log carries it, since that is what crosses branches")))))

;; --- a rejected prolog artifact must SAY it was rejected ---------------------

(deftest an-unfaithful-prolog-artifact-is-reported-as-a-failure-with-the-reason
  ;; Watched this cost two consecutive turns live. A branch posted its
  ;; constraints inside \+, the deterministic check caught it and journalled
  ;; the objection, the artifact was marked :unfaithful — and the branch was
  ;; told "The goal succeeded with 1 solution(s)". So it repeated the identical
  ;; defect on the very next turn, because from where it sat the turn had
  ;; worked.
  ;;
  ;; verify_smt, verify_octave and verify_lean all explain an :unfaithful
  ;; outcome. The prolog path set the status and said nothing, and worse,
  ;; still reported :success and scored progress for it.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (state/new-branch {:id "B1" :problem "p"})
        ;; posts a clpfd constraint inside \+, which undoes it
        bad "ok :- Xs ins 0..1, \\+ ( member(X,Xs), X #>= 1 ), label(Xs)."]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [prolog/assert-rules! (fn [& _] {:ok true :clauses 1})
                  prolog/snapshot (fn [& _] [{:code bad}])
                  prolog/query (fn [& _] {:ok true :answers [{:bindings {:M 2}
                                                              :formatted "M = 2"}]})
                  llm/chat (fn [& _] (throw (ex-info "judge must not be called" {})))]
      (let [r (tools/run-tool {:branch b :turn 5 :conn c :run-id rid
                               :tool-name "verify"
                               :args {:claim "the minimum cost is 2" :check "ok"}})]
        (is (= :unfaithful (get-in r [:artifact :claim-status])))
        (is (= :failure (:category r))
            "a rejected artifact is not a success")
        (is (false? (:progress? r))
            "and it does not score progress")
        (is (re-find #"(?i)constraint inside" (:result r))
            "the result names the construct that swallowed the constraints")
        (is (re-find #"(?i)does not|not established|enforc" (:result r))
            "and says the goal did not establish the claim")
        (is (re-find #"(?i)constraint inside" (str (get-in r [:failure :reason])))
            "and the objection reaches the cross-branch failure log, which is
             what stops a sibling repeating it")))))

;; --- the deterministic gate blocks before the judge is paid for -------------

(deftest a-structural-objection-blocks-without-calling-the-judge
  ;; The whole point of the deterministic checks is that they are free and
  ;; certain. If a defect can be settled by arithmetic, asking a reviewer is
  ;; both wasted money and an opportunity for it to be talked out of the
  ;; objection — the drift failure in vf-5wi. So llm/chat throws here: the
  ;; test passes only if it is never reached.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (state/new-branch {:id "B1" :problem "p"})
        L 3281866875
        term (fn [coef i] (str "(ite (= y" i " 0) " coef " 0)"))
        good [(quot (* 15 L) 45) (quot (* 15 L) 2835)
              (quot (* 3 L) 9) (quot (* 5 L) 25) (quot L 7)]
        ;; 17361625 is gen-13's actual typo; 15L/2835 = 17364375.
        smt (str "(assert (>= (+ " (str/join " " (map term good (range)))
                 " " (term 17361625 99) ") " L "))(check-sat)")]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [smt/run-smt (fn [& _] {:status :ok :verdict :unsat})
                  llm/chat (fn [& _] (throw (ex-info "judge must not be called" {})))]
      (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                               :tool-name "verify_smt"
                               :args {:claim "the layered condition with L = 3281866875"
                                      :smtlib smt :expectedVerdict "unsat"}})]
        (is (= :unfaithful (get-in r [:artifact :claim-status]))
            "a coefficient matching no modulus blocks the confirmation")))
    (let [evs (filter #(= "structural-objection" (:kind %)) (journal/events-since c rid 0))]
      (is (= 1 (count evs)) "and the objection is journalled")
      (is (str/includes? (:data (first evs)) "17361625")
          "naming the coefficient, so the branch's next turn can fix it"))))

;; --- a judge verdict has to say why -----------------------------------------

(deftest a-judge-verdict-is-journalled-with-its-reasoning
  ;; A rejection used to leave `claim-status = unfaithful` and nothing else.
  ;; When gen-14's mod-105 computation was rejected — arithmetic independently
  ;; confirmed correct, every one of its eight constants right — there was no
  ;; way to tell from the journal whether the reviewer had caught a real
  ;; mismatch or misfired, which is precisely the question that decides
  ;; whether the gate is worth having. A gate whose false positives are
  ;; invisible cannot be tuned.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (state/new-branch {:id "B1" :problem "p"})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [smt/run-smt (fn [& _] {:status :ok :verdict :unsat})
                  llm/chat (fn [& _]
                             {:content (str "<think>weighing it up</think>\n"
                                            "GAP: the encoding pins y0 and the claim does not\n"
                                            "VERDICT: FAIL")})]
      (tools/run-tool {:branch b :turn 4 :conn c :run-id rid
                       :tool-name "verify_smt"
                       :args {:claim "every element of S satisfies P" :smtlib "(assert (>= x 1))(check-sat)"
                              :expectedVerdict "unsat"}}))
    (let [evs (filter #(= "judge-verdict" (:kind %)) (journal/events-since c rid 0))
          d (some-> (first evs) :data)]
      (is (= 1 (count evs)) "the verdict is recorded")
      (is (str/includes? d "faithfulness")
          "labelled with which question was asked, since three callers share the judge")
      (is (str/includes? d "fail") "carrying the verdict")
      (is (str/includes? d "pins y0")
          "and the reasoning, which is the whole point — without it a rejection
           cannot be told from a misfire")
      (is (not (str/includes? d "weighing it up"))
          "the think block is dropped: it is bulk, and the parser already ignores it"))))

(deftest a-judge-verdict-that-passes-is-journalled-too
  ;; Only recording rejections would make the gate look worse than it is and
  ;; leave no denominator for a false-positive rate.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (state/new-branch {:id "B1" :problem "p"})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [smt/run-smt (fn [& _] {:status :ok :verdict :unsat})
                  llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
      (tools/run-tool {:branch b :turn 4 :conn c :run-id rid
                       :tool-name "verify_smt"
                       :args {:claim "every element of S satisfies P" :smtlib "(assert (>= x 1))(check-sat)"
                              :expectedVerdict "unsat"}}))
    (is (= 1 (count (filter #(= "judge-verdict" (:kind %))
                            (journal/events-since c rid 0)))))))

;; --- the other three engines face the same two layers -----------------------

(defn- fresh-run []
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    [c rid (state/new-branch {:id "B1" :problem "p"})]))

(deftest every-engine-puts-its-objection-in-the-shared-failure-log
  ;; vf-9p2, and the second half of vf-i26. verify and verify_smt carried the
  ;; reviewer's objection across branches; verify_octave, verify_lean,
  ;; verify_template and measure recorded a generic sentence and let the
  ;; reasoning die with the branch that earned it.
  ;;
  ;; The cost, measured in run 0d0c3560: FOUR branches independently wrote a
  ;; counter over feasible flows, called it a count of L1-optimal flows, and
  ;; were each told separately that they had dropped the cost-minimality
  ;; constraint. The failure log exists so the second one does not have to
  ;; make the first one's mistake, and it was carrying "the Octave expression
  ;; does not answer the claim".
  (let [objection (str "GAP: the artifact counts FEASIBLE flows and never"
                       " encodes edge costs, so it does not count L1-optimal"
                       " flows.\nVERDICT: FAIL")
        judge (fn [& _] {:content objection})
        carries? (fn [label r]
                   (is (= :unfaithful (get-in r [:artifact :claim-status])) label)
                   (is (re-find #"(?i)never encodes edge costs" (str (:result r)))
                       (str label ": the branch is told why"))
                   (is (re-find #"(?i)never encodes edge costs"
                                (str (get-in r [:failure :reason])))
                       (str label ": and so is every sibling")))]

    (testing "verify_octave"
      (let [[c rid b] (fresh-run)]
        (with-redefs [octave/create-session (fn [& _] {:dir "/tmp/x" :log (atom [{:code "n = count();"}])
                                                       :alive (atom true)})
                      octave/check (fn [& _] {:ok true :verdict true :tol 0 :exact true})
                      llm/chat judge]
          (carries? "verify_octave"
                    (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                     :tool-name "verify_octave"
                                     :args {:claim "there are 8 optimal flows" :expr "n == 8"}})))))

    (testing "measure"
      (let [[c rid b] (fresh-run)]
        (with-redefs [octave/create-session (fn [& _] {:dir "/tmp/x" :log (atom [{:code "n = count();"}])
                                                       :alive (atom true)})
                      octave/measure (fn [& _] {:ok true :value 8 :text "8"})
                      llm/chat judge]
          (carries? "measure"
                    (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                     :tool-name "measure"
                                     :args {:claim "the number of optimal flows" :expr "n"}})))))

    (testing "verify_lean"
      (let [[c rid b] (fresh-run)]
        (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                      lean-repl/mathlib-env (fn [& _] nil)
                      lean-pool/checkout! (fn [& _] {:id "s"})
                      lean-repl/run-command (fn [& _] {:ok true :sorries []})
                      llm/chat judge]
          (carries? "verify_lean"
                    (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                     :tool-name "verify_lean"
                                     :args {:claim "there are 8 optimal flows"
                                            :lean "theorem foo : 1 + 1 = 2 := by rfl"}})))))))

(deftest an-octave-check-over-literals-is-blocked-before-the-judge
  ;; gen-13 a#344: a CONFIRMED artifact whose whole code was `1.014488 > 1`.
  ;; The glpk solve behind that number ran on an earlier turn and appears
  ;; nowhere in the row, and the expression would have confirmed any claim
  ;; stapled to it. Octave had neither layer at the time. llm/chat throws, so
  ;; this passes only if the deterministic check settles it first.
  (let [[c rid b] (fresh-run)]
    (with-redefs [octave/create-session (fn [& _] {:dir "/tmp/x" :log (atom [])
                                                   :alive (atom true)})
                  octave/check (fn [& _] {:ok true :verdict true :tol 0 :exact true})
                  llm/chat (fn [& _] (throw (ex-info "judge must not be called" {})))]
      (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                               :tool-name "verify_octave"
                               :args {:claim "the LP dual value exceeds 1"
                                      :expr "1.014488 > 1"}})]
        (is (= :unfaithful (get-in r [:artifact :claim-status])))
        (is (= :failure (:category r)))))))

(deftest an-octave-artifact-carries-the-workspace-that-produced-its-numbers
  ;; The Prolog artifact had this exact defect and the same fix: a goal, or an
  ;; expression, is meaningless without the definitions behind it.
  (let [[c rid b] (fresh-run)
        log (atom [{:code "A = solve_lp();"} {:code "val = A(1);"}])]
    (with-redefs [octave/create-session (fn [& _] {:dir "/tmp/x" :log log
                                                   :alive (atom true)})
                  octave/check (fn [& _] {:ok true :verdict true :tol 0 :exact true})
                  llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
      (let [code (-> (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                      :tool-name "verify_octave"
                                      :args {:claim "the LP value exceeds 1"
                                             :expr "val > 1"}})
                     :artifact :code)]
        (is (str/includes? code "A = solve_lp();"))
        (is (str/includes? code "val > 1"))))))

;; --- measurements are evidence, of a kind that is not proof ------------------
;;
;; vf-0of. The phase-unwrapping run made 84 octave_eval calls and banked
;; nothing from any of them, and the branch whose whole thesis was "empirically
;; locate sigma_MCF" was culled at turn 12 having done most of that simulation.
;; Everything the critic scores is a confirmed artifact; the only route to one
;; is a verify_* that needs a decidable claim, and verify_octave wants a scalar
;; logical. "Recovery breaks near sigma = 0.7" is not one, so the most valuable
;; output available scored zero and the beam walked downhill into whatever was
;; easiest to state as a proposition.

(defn- measuring [value text judge-reply]
  (fn [f]
    (with-redefs [octave/create-session (fn [& _] {:dir "/tmp/x"
                                                   :log (atom [{:code "rate = sweep ();"}])
                                                   :alive (atom true)})
                  octave/measure (fn [& _] {:ok true :value value :text text})
                  llm/chat (fn [& _] {:content judge-reply})]
      (f))))

(deftest a-measurement-banks-as-evidence
  (let [[c rid b] (fresh-run)]
    ((measuring 0.72 "0.72" "GAPS: none\nVERDICT: PASS")
     (fn []
       (let [r (tools/run-tool
                {:branch b :turn 1 :conn c :run-id rid :tool-name "measure"
                 :args {:claim (str "the exact-recovery rate at sigma = 0.7,"
                                    " n = 32, over 200 trials")
                        :expr "rate"}})]
         (is (= :empirical (get-in r [:artifact :claim-status]))
             "a measurement is banked, and is its own status — not a proof")
         (is (= :success (:category r)))
         (is (true? (:progress? r))
             "so a branch measuring things is not a branch standing still")
         (is (str/includes? (get-in r [:artifact :claim]) "0.72")
             "the value Octave returned is written into the claim, so no artifact
              can cite a number the run never computed")
         (is (= {:value 0.72} (get-in r [:artifact :witness])))
         (is (str/includes? (get-in r [:artifact :code]) "rate = sweep ();")
             "with the workspace that produced it, like every other Octave row"))))))

(deftest a-measurement-is-not-a-proof-and-cannot-ship-as-one
  (let [measured {:claim "the recovery rate at sigma = 0.7 is 0.72 (measured: 0.72)"
                  :claim-status :empirical :kind :octave :tier :fast
                  :witness {:value 0.72} :code "rate"}
        b (branch-with :artifacts [measured]
                       :last-audit {:passed true :proposed-answer "a"})
        r (tools/run-tool {:branch b :turn 1 :tool-name "done" :args {:answer "a"}})]
    (is (= :failure (:category r)))
    (is (str/includes? (:result r) "no confirmed artifact")
        "the done gate still wants something an engine decided")))

(deftest a-measured-number-covers-an-answer-that-cites-it
  ;; The coverage gate exists to catch fabricated numbers. A number Octave
  ;; computed and the harness recorded is not fabricated, so it covers — which
  ;; is what lets a branch state its measurement in the answer at all.
  (let [measured {:claim "the recovery rate at sigma = 0.7 is 0.72"
                  :claim-status :empirical :witness {:value 0.72} :code "rate"}]
    (is (empty? (tools/uncovered-tokens "the recovery rate is 0.72" [measured])))
    (is (= ["0.91"] (tools/uncovered-tokens "the recovery rate is 0.91" [measured])))))

(deftest a-measurement-that-does-not-answer-its-claim-is-refused
  ;; The faithfulness layer applies here exactly as it does to a verification.
  ;; A claim quantified over an infinite family is not reached by a
  ;; computation, however many points it covers, and a measurement is the
  ;; easiest place in the harness to forget that.
  (let [[c rid b] (fresh-run)]
    ((measuring 0.72 "0.72" "GAP: the claim is about all n\nVERDICT: FAIL")
     (fn []
       (let [r (tools/run-tool
                {:branch b :turn 1 :conn c :run-id rid :tool-name "measure"
                 :args {:claim "recovery holds for every n" :expr "rate"}})]
         (is (= :unfaithful (get-in r [:artifact :claim-status])))
         (is (= :failure (:category r)))
         (is (str/includes? (:result r) "all n")
             "with the reviewer's objection, not a bare rejection"))))))

(deftest the-critic-is-shown-what-a-branch-measured
  ;; The load-bearing half. The critic's summary listed confirmed artifacts and
  ;; nothing else, so a branch three hours into a parameter sweep looked
  ;; identical to one that had done nothing.
  (let [b (branch-with :id "B1"
                       :thesis {:goal "locate the threshold empirically"}
                       :artifacts [{:claim "recovery rate at sigma = 0.7 is 0.72"
                                    :claim-status :empirical :kind :octave}])
        s (#'critic/summary b [])]
    (is (str/includes? s "0.72"))
    (is (re-find #"(?i)measure" s))))

(deftest measurements-render-in-the-residual-report-as-measurements
  (let [b (branch-with :id "B1" :thesis {:goal "prove G" :subClaims []}
                       :artifacts [{:claim "recovery rate at sigma = 0.7 is 0.72"
                                    :claim-status :empirical :kind :octave
                                    :tier :fast :turn 3}])
        report (state/build-residual-report {:branches [b] :failures [] :gate-tally []})
        text (state/render-residual-report report)]
    (is (= ["recovery rate at sigma = 0.7 is 0.72"]
           (map :claim (:measured (first (:branches report))))))
    (is (empty? (:established (first (:branches report))))
        "never in the established bucket")
    (is (str/includes? text "0.72"))
    (is (re-find #"(?i)measured.*not a proof|not a proof" text)
        "labeled for exactly what it does and does not substantiate")))

(deftest a-lean-theorem-that-is-not-the-claim-is-not-confirmed
  ;; Lean checks proofs, not statements. A declaration can elaborate perfectly
  ;; and be about something else — a narrower range, an unused hypothesis, a
  ;; definition that is subtly the wrong object.
  (let [[c rid b] (fresh-run)
        run (fn [judge-reply]
              (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                            lean-repl/mathlib-env (fn [& _] nil)
                            lean-pool/checkout! (fn [& _] {:id "s"})
                            lean-repl/run-command (fn [& _] {:ok true :sorries []})
                            llm/chat (fn [& _] {:content judge-reply})]
                (-> (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                     :tool-name "verify_lean"
                                     :args {:claim "every odd covering needs four primes"
                                            :lean "theorem foo : 1 + 1 = 2 := by rfl"}})
                    :artifact :claim-status)))]
    (is (= :unfaithful (run "GAP: proves 1+1=2, not the claim\nVERDICT: FAIL")))
    (is (= :confirmed (run "GAPS: none\nVERDICT: PASS")))))

(deftest a-lean-rejection-names-the-fault-not-just-the-error
  ;; vf-7k8. The rejection used to be the raw engine output plus one generic
  ;; sentence, and the same error classes recurred for three generations: 34
  ;; Lean failures across gen-22/23/24, of which 8 linarith/omega, 7 syntax,
  ;; 4 instance.
  ;;
  ;; All 8 arithmetic failures are the same mistake. The run is deriving
  ;; polynomial work bounds — 125*E^5*V^4 < (2*(E*S)+1)*(…) — from E ≤ 2V and
  ;; S ≤ 2V, which requires MULTIPLYING hypotheses, against tactics that are
  ;; linear by construction and cannot. Naming that is worth more than the
  ;; goal state it already prints, because the goal state looks the same
  ;; whether the tactic was wrong or the mathematics was.
  ;;
  ;; The hint names the fault and the tactic that addresses it. It does not do
  ;; the mathematics — same discipline as `missing` and `vague-claim`.
  (let [hint #'tools/lean-hint]
    (testing "linear tactics on a goal that multiplies unknowns"
      (let [h (hint (str "linarith failed to find a contradiction case h E V S : ℕ "
                         "hE : 1 ≤ E hS : S ≤ 2 * V a✝ : 125 * E ^ 5 * V ^ 4 < "
                         "(2 * (E * S) + 1) * (2 * E)"))]
        (is (some? h))
        (is (str/includes? h "nlinarith"))
        (is (or (str/includes? h "linear") (str/includes? h "multipl"))
            "says WHY the tactic could not do it")))

    (testing "omega reports the same limitation differently"
      (is (str/includes? (str (hint "omega could not prove the goal: a possible counterexample"))
                         "nlinarith")))

    (testing "the deprecated big-operator binder"
      (let [h (hint "unexpected token 'in'; expected ','")]
        (is (some? h))
        (is (str/includes? h "∈") "names the spelling Mathlib now wants")))

    (testing "indexing a function with bracket syntax"
      (let [h (hint (str "failed to synthesize instance of type class GetElem "
                         "(Fin 4 → ℤ) (Fin 4) ?m.5 ?m.6"))]
        (is (some? h))
        (is (str/includes? h "f i"))))

    (testing "a missing Fintype instance"
      (is (str/includes? (str (hint "failed to synthesize instance of type class Fintype A4"))
                         "deriving instance")))

    (testing "a rewrite whose pattern is not present"
      (is (str/includes? (str/lower-case
                          (str (hint "Tactic `rewrite` failed: Did not find an occurrence of the pattern")))
                         "syntactically")))

    (testing "an unknown constant is a name to look up, not a proof problem"
      (is (str/includes? (str (hint "unknown constant 'Finset.sum_sub_distrib'"))
                         "lean_search")))

    (testing "an error with no known class gets no invented advice"
      (is (nil? (hint "some entirely novel Lean failure nobody has seen")))))

  ;; And it reaches the branch.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          b (state/new-branch {:id "B1" :problem "p"})]
      (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                    lean-repl/mathlib-env (fn [& _] nil)
                    lean-pool/checkout! (fn [& _] {:id "s"})
                    lean-repl/run-command
                    (fn [& _] {:ok false :sorries []
                               :errors [{:data "linarith failed to find a contradiction"}]})]
        (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                 :tool-name "verify_lean"
                                 :args {:claim "the total work is at most 1000 * V^7"
                                        :lean "theorem t : True := by linarith"}})]
          (is (= :failure (:category r)))
          (is (str/includes? (:result r) "nlinarith")
              "the hint is in what the branch actually reads"))))))

(deftest a-lean-snippet-lean-rejects-records-no-artifact
  ;; It used to record one with claim-status :refuted, so a type error or a
  ;; failed tactic read as evidence AGAINST the claim. A proof that does not
  ;; compile says nothing about whether the statement is true. The failure log
  ;; keeps the record; the artifact table should not.
  (let [[c rid b] (fresh-run)]
    (with-redefs [lean-repl/create-session (fn [& _] {:id "s"})
                  lean-repl/mathlib-env (fn [& _] nil)
                  lean-pool/checkout! (fn [& _] {:id "s"})
                  lean-repl/run-command (fn [& _] {:ok false :sorries []
                                                   :errors [{:data "unknown identifier"}]})
                  llm/chat (fn [& _] (throw (ex-info "judge must not be called" {})))]
      (let [r (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                               :tool-name "verify_lean"
                               :args {:claim "every element of S satisfies P"
                                      :lean "theorem foo : True := nonsense"}})]
        (is (nil? (:artifact r)) "a broken proof is not a refutation")
        (is (= :failure (:category r)))))))

(deftest a-template-cross-check-does-not-stand-in-for-review
  ;; verify_template ran two encodings and told the model "this needs no
  ;; separate review." Both are built from the SAME model-supplied slots, so
  ;; agreement shows the instantiation was consistent and says nothing about
  ;; whether the values are the ones the claim names.
  (let [[c rid b] (fresh-run)
        run (fn [judge-reply]
              (with-redefs [smt/run-template
                            (fn [& _] {:status :ok :confirmed true :agreed true
                                       :note "both agree"
                                       :primary {:smtlib "(assert (>= n 7))(check-sat)"
                                                 :verdict :unsat :expected :unsat}
                                       :cross {:verdict :sat :expected :sat}})
                            llm/chat (fn [& _] {:content judge-reply})]
                (-> (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                     :tool-name "verify_template"
                                     :args {:claim "no n below 5 works"
                                            :template "t" :slots {:n 7}}})
                    :artifact :claim-status)))]
    (is (= :unfaithful (run "GAP: the slot is 7, the claim says 5\nVERDICT: FAIL")))
    (is (= :confirmed (run "GAPS: none\nVERDICT: PASS")))))

;; --- a truncated turn is retried, not spent ---------------------------------

(deftest a-response-truncated-before-any-tool-call-is-retried-at-a-doubled-budget
  ;; fence/signals already separates :truncated from :no-fence and says why:
  ;; a reply that hit the cap mid-thought never reached the fence, and "the fix
  ;; is more tokens, not more steering." The branch loop steered anyway, and
  ;; spent the turn. gen-12 opened with three of these in one round, gen-11 ran
  ;; a 12% no-call rate against gen-10's 4%.
  (let [calls (atom [])]
    (with-redefs [llm/chat (fn [_ _ _ & [opts]]
                             (swap! calls conj (:max-tokens opts))
                             (if (= 1 (count @calls))
                               {:content "thinking..." :finish-reason "length"}
                               {:content "```tool-call\n{\"name\":\"thesis\"}\n```"
                                :finish-reason "stop"}))]
      (let [ctx {:llm-adapter :a :llm-config {:max-tokens 16384}}
            r (#'aloop/call-model ctx {:messages []})]
        (is (true? (:ok r)))
        (is (= 2 (count @calls)) "the truncated call is retried rather than spent")
        (is (= [16384 32768] @calls)
            "and the retry doubles the budget instead of repeating it")
        (is (str/includes? (:content (:response r)) "tool-call")
            "the retry's response is the one returned")))))

(deftest a-truncated-response-that-still-carried-a-call-is-not-retried
  ;; Truncation only matters when it cost the tool call. A reply that emitted
  ;; its fence and then ran out of room is a complete turn.
  (let [calls (atom 0)]
    (with-redefs [llm/chat (fn [& _]
                             (swap! calls inc)
                             {:content "```tool-call\n{\"name\":\"thesis\"}\n```\nand then"
                              :finish-reason "length"})]
      (#'aloop/call-model {:llm-adapter :a :llm-config {:max-tokens 16384}} {:messages []})
      (is (= 1 @calls)))))

(deftest a-model-that-never-calls-a-tool-stops-after-the-doubled-attempt
  ;; The escalation is bounded: one retry, then the turn is spent as before.
  ;; An unbounded loop here would burn a branch's whole budget on one turn.
  (let [calls (atom 0)]
    (with-redefs [llm/chat (fn [& _]
                             (swap! calls inc)
                             {:content "still thinking" :finish-reason "length"})]
      (#'aloop/call-model {:llm-adapter :a :llm-config {:max-tokens 16384}} {:messages []})
      (is (= 2 @calls)))))

;; --- a turn that emitted no call is prefilled into the fence ----------------

(deftest a-turn-that-emitted-no-tool-call-prefills-the-next-one
  ;; gen-22 B1 spent 24 of its 44 turns on __no_call__ — more than half the
  ;; branch. It was told "[harness] No ```tool-call block in your response"
  ;; twenty-four times, which is the measurement: asking a model that just
  ;; wrote 109,360 characters without a fence to please emit one does not
  ;; work. Turn 42 is the shape of it — a full page of sound reasoning ending
  ;; "let me confirm the composition theorem a#712's exact statement", and
  ;; then nothing.
  ;;
  ;; arbiter/prefill-for already argues the general case: across gen-19 and
  ;; gen-20 the gates that changed behaviour were the ones that WITHHELD, and
  ;; ending the request mid-fence is the withholding form of an instruction —
  ;; the model cannot answer in prose because it is already inside a tool
  ;; call. That mechanism was reachable only from a gate decision, so it never
  ;; reached the branch with the most to gain from it.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (state/new-branch {:id "B1" :problem "p"})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [llm/chat (fn [& _] {:content "Let me confirm a#712 first."
                                      :finish-reason "stop"})]
      (let [after (aloop/run-turn {:conn c :run-id rid :max-turns 40
                                   :llm-adapter :a :llm-config {:max-tokens 16384}}
                                  b 1)]
        (is (= "```tool-call\n" (:prefill after))
            "the next request ends mid-fence, so prose is not an available reply")
        (is (not (str/includes? (:prefill after) "\"name\""))
            "bare: which tool to call is the branch's decision, not the harness's")))))

(deftest a-turn-that-called-a-tool-leaves-no-prefill-behind
  ;; The complement, and the one that would go wrong quietly: a branch that is
  ;; working normally must not be forced into a fence, or it can never write
  ;; the reasoning that earns the next call.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (state/new-branch {:id "B1" :problem "p"})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [llm/chat (fn [& _]
                             {:content (str "```tool-call\n"
                                            (json/write-str
                                             {:name "thesis"
                                              :args {:goal "settle Q-1"
                                                     :technique "scalarization"
                                                     :subClaims ["the box bound holds"]}})
                                            "\n```")
                              :finish-reason "stop"})]
      (let [after (aloop/run-turn {:conn c :run-id rid :max-turns 40
                                   :llm-adapter :a :llm-config {:max-tokens 16384}}
                                  b 1)]
        (is (nil? (:prefill after)))))))

(deftest a-prefilled-turn-that-still-emits-no-call-keeps-the-fence
  ;; One prefill is not a guarantee. A model can open the fence and then fail
  ;; to close it, and the recovery from that is the same recovery — not a
  ;; branch that silently reverts to prose on the turn after.
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (assoc (state/new-branch {:id "B1" :problem "p"})
                 :prefill "```tool-call\n")]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (with-redefs [llm/chat (fn [& _] {:content "{\"name\": " :finish-reason "length"})]
      (let [after (aloop/run-turn {:conn c :run-id rid :max-turns 40
                                   :llm-adapter :a :llm-config {:max-tokens 16384}}
                                  b 1)]
        (is (= "```tool-call\n" (:prefill after)))))))

(deftest done-lets-a-branch-cite-a-number-a-sibling-branch-confirmed
  ;; vf-b9c. The coverage rung read only (:artifacts branch). Artifacts
  ;; confirmed elsewhere in the run reach a branch through the shared-artifact
  ;; block and the crossover block — the PROMPT, never the artifact list — so
  ;; the harness showed a branch evidence and then refused the answer that
  ;; cited it.
  ;;
  ;; Run 0d0c3560, B4.2.2.2 T92: refused for stating `8` and `6`, which are the
  ;; m=3 optimal count and cost that its own parent B4.2.2 had verified
  ;; exhaustively in Prolog and already shipped in an accepted answer. The
  ;; child rewrote around them and, in the same edit, dropped a Lean theorem it
  ;; had proved. Every fork inherits its parent's reasoning and none of its
  ;; evidence, which penalises exactly the structure the beam is built on.
  (let [[c rid _] (fresh-run)
        _ (runs/open-branch! c rid {:branch-id "B2" :parent-id "B1"
                                    :created-at-turn 0})
        _ (journal/record-artifact!
           c rid {:branch-id "B1" :turn 3 :kind :prolog
                  :claim (str "The disjoint union of three two-path unit-weight"
                              " dipole gadgets has exactly 8 optimal integer"
                              " flows, each of total cost 6.")
                  :code "count(S)." :verdict :confirmed
                  :claim-status :confirmed :tier :slow})
        mine (str "For every m, the cost-optimal split vectors on m two-path"
                  " unit-weight dipole gadgets number exactly 2^m.")
        answer (str "The minimum cost flow on the dual grid is not unique: for"
                    " every m, the optimal integer flows on m two-path dipole"
                    " gadgets number exactly 2^m. In particular the three-gadget"
                    " instance has exactly 8 optimal flows, each of cost 6.")
        b (-> (branch-with :id "B2" :problem unwrap-problem
                           :thesis {:goal "count the optimal flows"
                                    :technique "t" :subClaims []}
                           :artifacts [{:claim mine :claim-status :confirmed
                                        :kind :lean :tier :slow :code "theorem"}]
                           :last-review {:passed true}
                           :last-audit {:passed true :proposed-answer answer
                                        :established answer :relaxation? false}))
        ship (fn [] (with-redefs [llm/chat (fn [& _] {:content "ASKS: x\nSUPPLIES: x\nVERDICT: PASS"})]
                      (tools/run-tool {:branch b :turn 9 :tool-name "done"
                                       :conn c :run-id rid
                                       :args {:answer answer}})))]
    (testing "the sibling's confirmed figures cover the answer"
      (let [r (ship)]
        (is (= :success (:category r))
            (str "refused: " (:result r)))
        (is (= :done (:status (:branch r))))))
    (testing "a figure nobody in the run verified is still refused"
      (let [invented (str answer " The nine-gadget instance has exactly 512.")
            b (assoc b :last-audit {:passed true :proposed-answer invented
                                    :established invented :relaxation? false})
            r (with-redefs [llm/chat (fn [& _] {:content "ASKS: x\nSUPPLIES: x\nVERDICT: PASS"})]
                (tools/run-tool {:branch b :turn 9 :tool-name "done"
                                 :conn c :run-id rid
                                 :args {:answer invented}}))]
        (is (= :failure (:category r)))
        (is (str/includes? (:result r) "512"))))))

(deftest an-engine-outage-is-not-the-branch-s-fault
  ;; gen-18 B3 was culled after six consecutive failures while pursuing the
  ;; reduction to a separable convex cost flow — the most promising line in the
  ;; run. One of those six was `Lean is unavailable`, which is a fact about the
  ;; process pool and says nothing about the branch. An outage must not spend a
  ;; branch's cull budget.
  (let [[c rid b] (fresh-run)
        down (fn [tool args]
               (with-redefs [lean-pool/checkout! (fn [& _] (throw (ex-info "pool down" {})))
                             lean-repl/create-session (fn [& _] (throw (ex-info "pool down" {})))
                             octave/create-session (fn [& _] (throw (ex-info "no octave" {})))
                             llm/chat (fn [& _] (throw (ex-info "judge must not be called" {})))]
                 (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                  :tool-name tool :args args})))]
    (doseq [[tool args] [["verify_lean" {:claim "every element of S satisfies P" :lean "theorem t : True := trivial"}]
                         ["octave_eval" {:code "x = 1;"}]]]
      (let [r (down tool args)]
        (is (= :neutral (:category r))
            (str tool " reported an outage as a branch failure: " (:result r)))
        (is (re-find #"(?i)unavailable" (str (:result r)))
            (str tool " should still say the engine is down"))))
    (testing "and it never raises the counter the cull gate reads"
      (let [b3 (assoc b :consecutive-failures 3)]
        (is (<= (:consecutive-failures
                 (state/record-outcome b3 {:category :neutral :progress? false}))
                3))))))

(deftest turns-that-run-clean-work-off-the-cull-budget
  ;; gen-18 B1 made three malformed tool calls, then three clean Octave runs
  ;; that produced dual potentials — and was culled on a counter last
  ;; incremented three turns earlier. `consecutive-failures` drives the cull
  ;; gate, so it has to mean consecutive. The guard against well-formed but
  ;; useless calls is `turns-since-progress`, which a neutral turn still
  ;; increments, so nothing is lost by letting a clean turn work the failure
  ;; count down.
  (let [b (reduce (fn [b _] (state/record-outcome b {:category :failure}))
                  (state/new-branch {:id "B1" :problem "p"})
                  (range 3))]
    (is (= 3 (:consecutive-failures b)))
    (let [b1 (state/record-outcome b {:category :neutral :progress? false})
          b2 (state/record-outcome b1 {:category :neutral :progress? false})]
      (is (= 2 (:consecutive-failures b1)) "one clean turn decays it")
      (is (= 1 (:consecutive-failures b2)) "and another")
      (is (= 5 (:turns-since-progress b2))
          "while the useless-call guard keeps counting")
      (is (= 0 (:consecutive-failures
                (state/record-outcome b2 {:category :neutral :progress? false})))
          "and it floors at zero rather than going negative"))
    (testing "sustained failure still accumulates"
      (let [b (reduce (fn [b c] (state/record-outcome b {:category c}))
                      b [:neutral :failure :failure :neutral :failure :failure])]
        (is (>= (:consecutive-failures b) 3))))))

(deftest a-malformed-fence-is-a-mechanics-fault-not-a-verification-failure
  ;; gen-20 B2 was culled at turn 6 having called `thesis` and `lean_search`
  ;; and nothing else. Its other four turns produced no ```tool-call block at
  ;; all, and each was recorded :failure — the same counter the cull gate
  ;; reads. The reason it died with says "the critic scored the line a dead
  ;; end", but the critic had no line to score: the branch never made a
  ;; substantive claim. A fifth of the beam went to a formatting problem.
  ;;
  ;; loop.clj already draws this distinction one branch up, for a provider
  ;; error: "not the branch's fault and must not count against it as a
  ;; verification failure." A fence the model malformed is the same kind of
  ;; thing — a mechanics fault, which the branch already tracks separately.
  (let [b2 (reduce (fn [b c] (state/record-outcome b {:category c :progress? false}))
                   (state/new-branch {:id "B2" :problem "p"})
                   ;; B2's actual sequence, turns 1-6.
                   [:neutral :mechanics :neutral :mechanics :mechanics :mechanics])]
    (is (zero? (:consecutive-failures b2))
        "four malformed fences must not read as verification failures")
    (is (= 3 (:consecutive-mechanics-failures b2))
        "but they are counted, on their own tally — consecutive, so the
         lean_search between the first and the rest resets it")
    (is (= 6 (:turns-since-progress b2))
        "and the useless-turn guard still counts every one of them")
    (is (< (:consecutive-mechanics-failures b2)
           (* 2 (gates/threshold :cull-threshold)))
        "and B2, the branch this is named for, now survives its turn 6")
    (testing "a well-formed call clears the mechanics tally — it proved it can"
      (doseq [c [:success :failure :neutral]]
        (is (zero? (:consecutive-mechanics-failures
                    (state/record-outcome b2 {:category c :progress? false})))
            (str "a " c " turn is a well-formed call and should clear it"))))
    (testing "a real verification failure still counts, after mechanics noise"
      (let [b (state/record-outcome b2 {:category :failure :progress? false})]
        (is (= 1 (:consecutive-failures b))
            "and starts from 1, not compounded by the malformed turns")))))

(deftest a-claim-has-to-be-a-statement-not-a-label
  ;; Three entries in the campaign's inherited set read "weighted sum yields
  ;; lex min", "coordinate bound from Q bound" and "one coordinate
  ;; scalarization". Each is the NAME of a theorem, not the theorem: a branch
  ;; reading the settled-state block learns nothing from them and has to spend
  ;; a fetch to discover it already holds that result. They are permanent —
  ;; the campaign inherits them forward — so the fix belongs at the point of
  ;; recording, where it is still cheap.
  ;;
  ;; Length alone is the wrong test. Short and precise is fine; long and empty
  ;; is not. What makes a claim usable later is that it asserts something —
  ;; a relation, a quantifier, a conditional.
  (testing "labels are refused, with the shape spelled out"
    (doseq [label ["weighted sum yields lex min"
                   "coordinate bound from Q bound"
                   "one coordinate scalarization"
                   "Nat.succ_inj is available."
                   "Simple Finset product environment check"]]
      (let [m (#'tools/vague-claim {:args {:claim label}})]
        (is (some? m) (str "should be refused: " label))
        (is (re-find #"(?i)state what is true" (str m))))))

  (testing "short but precise passes — a relation is an assertion"
    ;; A real confirmed claim from the odd-covering campaign.
    (doseq [ok ["uE({3,5,9,15}) >= 17 over period 45"
                "1 + 1 = 2"
                "no odd covering system has fewer than 4 primes"]]
      (is (nil? (#'tools/vague-claim {:args {:claim ok}}))
          (str "should pass: " ok))))

  (testing "a full statement passes on its own terms"
    (is (nil? (#'tools/vague-claim
               {:args {:claim (str "If k1 is C-minimal over finite set S and k2 is "
                                   "Q-minimal among elements with C equal to C k1, "
                                   "then Q(k2) <= Q(k1).")}}))))

  (testing "the check fires where artifacts are made"
    (let [b (state/new-branch {:id "B1" :problem "p"})
          r (tools/run-tool {:branch b :turn 1 :tool-name "verify_lean"
                             :args {:claim "one coordinate scalarization"
                                    :lean "theorem t : True := trivial"}})]
      ;; :mechanics, not :failure, since vf-v6x. The refusal happens BEFORE
      ;; any engine runs, so the branch has no verdict either way and there is
      ;; nothing here to hold against its line of inquiry. The count is still
      ;; kept, so a branch that keeps writing labels is still bounded.
      (is (= :mechanics (:category r)))
      (is (re-find #"(?i)state what is true" (:result r)))
      (is (nil? (:artifact r)) "and nothing is recorded under a label"))))

(deftest a-missing-expectedVerdict-is-an-annotation-fault-not-a-failed-proof
  ;; gen-21 B3 composed the previous run's two lemmas into a full three-stage
  ;; selector, ran Z3, got a real unsat — and was charged a verification
  ;; failure because it had not declared which verdict supported the claim.
  ;; It was culled a turn later with SIX failures, every one of them interface
  ;; friction and not one a failed proof.
  ;;
  ;; The engine ran and answered. Nothing about the branch's reasoning failed;
  ;; it omitted an annotation. That is the same distinction vf-jki drew for
  ;; malformed fences, in a different tool.
  ;;
  ;; UNKNOWN is left as a failure: there the engine genuinely could not decide,
  ;; which is a fact about the encoding the branch chose.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          b (state/new-branch {:id "B1" :problem "p"})
          run (fn [args] (tools/run-tool (merge {:branch b :turn 1 :conn c :run-id rid
                                                 :tool-name "verify_smt"
                                                 :config {:engines {}}}
                                                {:args args})))
          smt "(declare-const x Int)(assert (and (> x 2) (< x 1)))(check-sat)"]
      (let [r (run {:claim "no such x" :smtlib smt})]
        (is (= :mechanics (:category r))
            (str "a missing expectedVerdict must not spend cull budget: " (:category r)))
        (is (re-find #"expectedVerdict" (:result r)) "and must still say what is missing")
        (is (not (:progress? r)) "but it is not progress either"))
      (testing "a declared expectation leaves the annotation path entirely"
        ;; Offline this then goes to the faithfulness review, which needs a
        ;; sub-LLM, so the eventual category is not checkable here. What IS
        ;; checkable, and what this guards, is that declaring the expectation
        ;; stops the result being treated as a missing annotation.
        (let [r (run {:claim "no such x" :smtlib smt :expectedVerdict "unsat"})]
          (is (not= :mechanics (:category r)))
          (is (not (re-find #"did not declare expectedVerdict" (str (:result r))))))))))

(deftest proof-start-names-the-mistake-instead-of-forwarding-lean-s-confusion
  ;; proof_start appends " := by sorry" to open the goal, so a theorem that
  ;; already carries a proof body becomes `… := by tac := by sorry` and Lean
  ;; answers "unexpected token ':='". That error describes a parse, not the
  ;; mistake, and the model made it three times in the first 29 turns of one
  ;; run despite the prompt saying statement-only in as many words.
  ;;
  ;; Caught before Lean runs: no session, no round trip, and a message that
  ;; names the fix.
  (let [b (state/new-branch {:id "B1" :problem "p"})
        call (fn [thm] (tools/run-tool {:branch b :turn 1 :tool-name "proof_start"
                                        :args {:claim "every element of S satisfies P" :theorem thm}}))]
    (let [r (call "theorem foo : True := by trivial")]
      (is (= :failure (:category r)))
      (is (re-find #"(?i)statement only|without the proof" (:result r))
          (str "must name what is wrong: " (:result r)))
      (is (not (re-find #"unexpected token" (:result r)))
          "and must not forward Lean's parse error for a mistake we can see")
      (is (re-find #"theorem foo : True" (:result r))
          "showing the statement it should have been given"))
    (testing "the shorthand body form is caught too"
      (is (= :failure (:category (call "theorem foo : True := trivial")))))
    (testing "a genuine statement is not blocked"
      ;; No :=, so nothing to complain about. It will fail later for want of a
      ;; Lean session in this test, which is not this guard's business — what
      ;; matters is that it is not refused HERE.
      (let [r (call "theorem foo (n : Nat) : n + 0 = n")]
        (is (not (re-find #"(?i)statement only" (str (:result r)))))))))

(deftest fetch-turn-opens-a-line-of-the-compacted-digest
  ;; Compaction unloads a branch's early turns to one line each and tells it
  ;; the detail is in the journal — but no tool took a turn number, so `t8
  ;; verify_smt → failure` named something the branch could not open.
  ;; Advertising a lookup that does not exist is worse than not offering one.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          b (state/new-branch {:id "B1" :problem "p"})]
      (journal/record-turn! c rid {:branch-id "B1" :turn 8 :tool-name "verify_smt"
                                   :args {:claim "the box contains every optimum"
                                          :smtlib "(assert (> x 2))"}
                                   :result "Z3 says unsat. That contradicts your expectedVerdict."
                                   :category :failure
                                   :assistant-text "<think>lots of reasoning</think>\nI will try the box bound."})
      (journal/record-turn! c rid {:branch-id "B2" :turn 8 :tool-name "verify_lean"
                                   :args {} :result "someone else's turn"
                                   :category :failure})
      (let [call (fn [args] (tools/run-tool {:branch b :turn 20 :conn c :run-id rid
                                             :tool-name "fetch_turn" :args args}))
            r (call {:turn 8})]
        (is (re-find #"verify_smt" (:result r)) "the tool that was called")
        (is (re-find #"the box contains every optimum" (:result r)) "its arguments")
        (is (re-find #"Z3 says unsat" (:result r)) "and what came back")
        (is (= :neutral (:category r))
            "a lookup is not progress and must not clear the failure count")
        (is (not (:progress? r)))
        (is (nil? (:artifact r)))
        (testing "reasoning is not reloaded — it is 96% of stored text and was stripped for a reason"
          (is (not (re-find #"lots of reasoning" (:result r))))
          (is (re-find #"I will try the box bound" (:result r))))
        (testing "a branch reads its OWN history, not a sibling's"
          (is (not (re-find #"someone else's turn" (:result r)))))
        (testing "a turn that does not exist says so"
          (let [r (call {:turn 999})]
            (is (= :failure (:category r)))
            (is (re-find #"(?i)no turn 999" (:result r)))))
        (testing "a missing argument is the standard complaint"
          (is (re-find #"(?i)missing required argument" (:result (call {})))))))))

(deftest fetch-artifact-turns-a-ledger-id-into-an-encoding
  ;; The ledger lists claims with ids and leaves encodings out, so the code
  ;; costs a turn only when a branch wants it. That only works if an id is
  ;; actionable — nothing could turn one into an encoding before.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          _ (journal/record-artifact! c rid
                                      {:branch-id "B2" :turn 3 :kind :lean
                                       :tier :slow :claim "the lex minimum exists"
                                       :code "theorem lex_min : True := trivial"
                                       :claim-status :confirmed})
          aid (:id (first (journal/artifacts c rid)))
          b (state/new-branch {:id "B1" :problem "p"})
          call (fn [args] (tools/run-tool {:branch b :turn 4 :conn c :run-id rid
                                           :tool-name "fetch_artifact" :args args}))]
      (let [r (call {:id aid})]
        (is (re-find #"lex_min" (:result r)) "the encoding comes back")
        (is (re-find #"the lex minimum exists" (:result r)) "with its claim")
        (is (= :neutral (:category r))
            "a lookup is not progress — :success would clear the failure count")
        (is (not (:progress? r)))
        (is (nil? (:artifact r)) "and it must not mint an artifact of its own"))

      (testing "a refuted artifact is fetchable too — that is the point"
        ;; The encoding is what shows WHY a line is closed, and is what stops a
        ;; sibling re-attempting it.
        (journal/record-artifact! c rid
                                  {:branch-id "B3" :turn 4 :kind :smt :tier :fast
                                   :claim "the strengthening holds"
                                   :code "(assert false)"
                                   :claim-status :refuted})
        (let [rid2 (:id (last (journal/artifacts c rid)))
              r (call {:id rid2})]
          (is (re-find #"assert false" (:result r)))
          (is (re-find #"(?i)refuted" (:result r))
              "and its status travels with it, or it reads as established")))

      (testing "a bare number finds a seeded row too, prefix or not"
        ;; Observed live: the ledger renders `s#649`, the model passes `649`,
        ;; and a bare number that only looked in this run's own artifacts
        ;; failed — six of the first eleven fetches in one run. Dropping a
        ;; prefix is the obvious thing for a model to do and costs a turn
        ;; every time, so a bare id falls back to the shared pool rather than
        ;; insisting on punctuation.
        (let [src (runs/start-run! c {:problem "prior"})
              rid2 (runs/start-run! c {:problem "q"})]
          (journal/record-artifact! c src {:branch-id "B1" :turn 1 :kind :lean
                                           :tier :slow :claim "inherited lemma"
                                           :code "theorem inh : True := trivial"
                                           :claim-status :confirmed})
          (artifacts/seed-from-run! c rid2 src)
          (let [sid (:id (first (artifacts/recent c rid2 5)))
                call2 (fn [args] (tools/run-tool {:branch b :turn 1 :conn c
                                                  :run-id rid2
                                                  :tool-name "fetch_artifact"
                                                  :args args}))]
            (is (re-find #"theorem inh" (:result (call2 {:id sid})))
                "a bare seeded id resolves")
            (is (re-find #"theorem inh" (:result (call2 {:id (str "s#" sid)})))
                "and so does the explicit form")
            (testing "and it echoes the handle it actually came from"
              ;; Echoing a#649 for a seeded row taught the model a handle that
              ;; then fails, since an explicit a# forces the artifacts table.
              (let [r (:result (call2 {:id sid}))]
                (is (re-find (re-pattern (str "s#" sid)) r))
                (is (not (re-find (re-pattern (str "a#" sid)) r)))))
            (testing "a seeded row reports its status rather than a blank"
              ;; shared_artifacts has no claim_status column, so this read as
              ;; "status " with nothing after it. seed-from-run! copies only
              ;; confirmed artifacts, so stating that is accurate.
              (is (re-find #"(?i)confirmed.*inherited"
                           (:result (call2 {:id sid}))))))))

      (testing "an unknown id fails rather than inventing something"
        (let [r (call {:id 999999})]
          (is (= :failure (:category r)))
          (is (re-find #"(?i)no artifact" (:result r)))))

      (testing "a missing id is the standard missing-argument complaint"
        (is (re-find #"(?i)missing required argument" (:result (call {}))))))))

(deftest a-gate-that-names-one-tool-forces-its-fence
  ;; gen-19 settled gate predictions 4 met to 22 unmet, gen-20 9 to 27. The
  ;; pattern across both is that a gate which WITHHOLDS something changes
  ;; behaviour and a gate which SUGGESTS one does not — the audit gate's
  ;; refusals redirected work, milestone and tier-escalation went 0-for-4.
  ;;
  ;; A prefill is the withholding version of a suggestion: the request ends
  ;; mid-fence, so the model cannot answer in prose. Applied only where a gate
  ;; already fired, not on every turn — prefilling makes the response BEGIN
  ;; with the call, which is right when the harness is already steering and
  ;; wrong as a blanket default.
  (testing "a gate whose prediction names exactly one tool carries it"
    (doseq [g [:branch-out :repopulate]]
      (is (= "branch_theses" (:tool (get gates/by-name g)))
          (str g " predicts 'the branch calls branch_theses' and should say so"))))
  (testing "a gate naming a choice or a behaviour carries none"
    ;; milestone predicts "review or done", stuck predicts a change of
    ;; technique. Forcing either would be the harness picking, not steering.
    (doseq [g [:milestone :stuck :progress-stalled :turn-budget]]
      (is (nil? (:tool (get gates/by-name g)))
          (str g " names no single tool and must not force one"))))
  (testing "the prefill is the opening fence, plus the name when there is one"
    (is (= "```tool-call\n{\"name\": \"branch_theses\""
           (arbiter/prefill-for {:tool "branch_theses"})))
    (is (= "```tool-call\n" (arbiter/prefill-for {:tool nil}))
        "a gate with no named tool still forecloses prose")
    (is (nil? (arbiter/prefill-for nil))
        "and no gate at all means no prefill")))

(deftest a-malformed-call-does-not-spend-cull-budget
  ;; vf-v6x, and the fifth place this pattern has turned up: fences,
  ;; expectedVerdict, proof_start, engine outages, and now argument shape. A
  ;; branch that mis-serialised its arguments has produced no claim and tested
  ;; nothing, so there is no evidence here about its line of inquiry — but
  ;; `fail` marks it :failure and the cull counter reads that as a dead line.
  ;;
  ;; Across gen-22/23/24, ~21 of 213 failure-turns are pure argument shape, 16
  ;; of them branch_theses emitting the byte-identical complaint. gen-24 culled
  ;; five of eight branches, several "after 3 consecutive failures".
  ;;
  ;; :mechanics, not :neutral — the count is still kept, so a branch looping on
  ;; malformed calls is still bounded. It just stops reading as mathematics.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          b (state/new-branch {:id "B1" :problem "p"})
          run (fn [tool args] (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                                               :tool-name tool :args args}))]
      (testing "a missing required argument"
        (are [tool args] (= :mechanics (:category (run tool args)))
          "verify_smt"    {:smtlib "(assert true)"}
          "verify_lean"   {:lean "theorem t : True := trivial"}
          "verify_octave" {:expr "x == 1"}
          "audit"         {:claim "every element of S satisfies P"}
          "review"        {:claim "every element of S satisfies P"}
          "fetch_turn"    {}))

      (testing "branch_theses with a malformed theses array"
        ;; The one that actually did the damage: 16 identical complaints.
        (are [args] (= :mechanics (:category (run "branch_theses" args)))
          {}
          {:theses []}
          {:theses "not an array"}))

      (testing "a real verification failure is still a failure"
        (with-redefs [smt/run-smt (fn [& _] {:status :ok :verdict :sat})
                      llm/chat (fn [& _] {:content "GAPS: none\nVERDICT: PASS"})]
          (is (= :failure (:category (run "verify_smt"
                                          {:claim "the minimum cost is 2"
                                           :smtlib "(assert (= x 1))"
                                           :expectedVerdict "unsat"})))
              "a refutation is evidence about the claim and must still count"))))))

(deftest a-missing-argument-error-shows-the-call-it-wanted
  ;; gen-20 B1 called proof_start without its required arguments five times,
  ;; three of them producing the byte-identical error, and was culled. The
  ;; message was a bare list of names — "Missing required argument(s): claim,
  ;; theorem." — which tells a model that did not understand the call the
  ;; first time nothing it did not already have.
  ;;
  ;; The skeleton needs no schema registry: `missing` is handed the tool name
  ;; and the keys it requires, which is exactly the call it is complaining
  ;; about.
  (let [msg (#'tools/missing {:tool-name "proof_start" :args {}} :claim :theorem)]
    (is (re-find #"claim" msg))
    (is (re-find #"theorem" msg))
    (is (re-find #"proof_start" msg)
        "the message must name the tool, not just the absent keys")
    (is (re-find #"\{" msg)
        "and show the shape of a valid call")
    (testing "an argument that was supplied is not demanded again"
      (let [m (#'tools/missing {:tool-name "proof_start" :args {:claim "every element of S satisfies P"}}
                               :claim :theorem)]
        (is (not (re-find #"claim," m)))
        (is (re-find #"theorem" m))))
    (testing "nothing missing is still nil, not an empty complaint"
      (is (nil? (#'tools/missing {:tool-name "proof_start"
                                  :args {:claim "every element of S satisfies P" :theorem "t"}}
                                 :claim :theorem))))))

(deftest repeating-a-failed-call-gets-a-different-answer
  ;; 29 of gen-20's 57 failures were four identical (tool, message) pairs. The
  ;; harness's reply to the fifth identical mistake was its reply to the first,
  ;; so a branch in a loop had nothing to work with. B1 — the branch that had
  ;; independently rediscovered the greedy characterisation — died in one.
  ;;
  ;; Exact comparison over (tool, message), not text similarity: both are
  ;; already recorded, it needs no threshold, and it cannot fire on an honest
  ;; retry that changed the call.
  (let [b (state/new-branch {:id "B1" :problem "p"})
        once (state/add-turn b {:turn 1 :tool "proof_start" :category :failure
                                :error "Missing required argument(s): claim."})]
    (is (not (state/repeating-failure? once "proof_start"
                                       "Missing required argument(s): claim."))
        "one failure is not a repeat")
    (let [twice (state/add-turn once {:turn 2 :tool "proof_start"
                                      :category :failure
                                      :error "Missing required argument(s): claim."})]
      (is (state/repeating-failure? twice "proof_start"
                                    "Missing required argument(s): claim.")
          "the same call failing the same way twice is")
      (testing "a different error on the same tool is not a repeat"
        (is (not (state/repeating-failure? twice "proof_start" "Lean rejected it"))))
      (testing "the same error from a different tool is not a repeat"
        (is (not (state/repeating-failure? twice "verify_lean"
                                           "Missing required argument(s): claim."))))
      (testing "a success in between clears it — the branch changed something"
        (let [recovered (state/add-turn twice {:turn 3 :tool "proof_start"
                                               :category :success})]
          (is (not (state/repeating-failure? recovered "proof_start"
                                             "Missing required argument(s): claim."))))))))

(deftest a-branch-that-cannot-emit-a-tool-call-is-still-bounded
  ;; The counter has to be separated, not softened. `mechanics` feeds only the
  ;; capability tier and gates nothing; `turns-since-progress` feeds only
  ;; progress-stalled, a nudge gate that gen-19 settled 0-for-2. So if a
  ;; malformed fence simply stopped counting, a branch emitting nothing but
  ;; garbage would hold a beam slot until the turn budget ran out.
  ;;
  ;; Deliberately looser than the verification threshold: three bad fences is
  ;; a model having a bad turn, and the branch has already shown it can call a
  ;; tool. Twice that is a branch that cannot work the protocol.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          ctx {:conn c :run-id rid}
          threshold (gates/threshold :cull-threshold)
          babbling (fn [n]
                     (-> (branch-with :id "BM" :consecutive-mechanics-failures n)
                         (assoc :turns (vec (repeat 8 {})))))]
      (is (state/active? (#'beam/cull-or-keep ctx (babbling threshold) 2 []))
          "at the verification threshold a mechanics-only branch keeps going")
      (let [dead (#'beam/cull-or-keep ctx (babbling (* 2 threshold)) 2 [])]
        (is (= :culled (:status dead)))
        (is (re-find #"(?i)tool call|fence|malformed" (:inactive-reason dead))
            (str "the reason must name the real cause, not a dead-end line: "
                 (:inactive-reason dead))))
      (testing "and the last branch standing is never culled for it either"
        (is (state/active? (#'beam/cull-or-keep ctx (babbling (* 4 threshold)) 0 [])))))))

(deftest an-encoding-that-proves-more-than-the-claim-still-proves-the-claim
  ;; gen-18 B4 T33. The claim was a theorem about a finite set D; the encoding
  ;; declared D as an unconstrained sort, so Z3's unsat proved it for every D,
  ;; finite or not. The judge failed it for formalising "a stronger statement
  ;; than the claimed finite-set theorem" — but a proof of the general
  ;; statement entails the finite case. That was one of the three strikes that
  ;; culled the leading Q-A branch.
  ;;
  ;; The polarity is what the prompt has to get right. Proving a universal by
  ;; refuting its negation: DROPPING a hypothesis makes the theorem harder and
  ;; the claim still follows, so PASS. ADDING one makes it easier and the claim
  ;; does not follow, so FAIL. For a counterexample the rule inverts — the
  ;; witness has to satisfy every hypothesis the claim makes — and a
  ;; measurement has to be at the claim's own parameters.
  (let [prompt (fn [direction]
                 (#'tools/faithfulness-prompt
                  "for any finite set D ..." "(declare-sort D 0)"
                  {:engine "an SMT-LIB encoding"
                   :outcome "Z3 returned unsat"
                   :direction direction}))]
    (testing "proving: a strictly more general formalisation passes"
      (let [p (prompt :proves)]
        (is (re-find #"(?i)more general" p)
            "the proving prompt has to say generality is allowed")
        (is (re-find #"(?i)claim follows|entails" p)
            "and say why: the claim follows from the stronger statement")
        (is (re-find #"(?i)extra hypothes|additional hypothes|narrow" p)
            "while still refusing an encoding that assumes more than the claim")))
    (testing "refuting: generality is not licensed, the witness must be in scope"
      (let [p (prompt :refutes)]
        (is (not (re-find #"(?i)more general" p))
            "a counterexample outside the claim's hypotheses refutes nothing")))
    (testing "measuring: unchanged"
      (is (not (re-find #"(?i)more general" (prompt :measures)))))))

(deftest on-start-fires-before-the-beam-opens-its-branches
  ;; api.control/start-run! blocks on (deref promised 30000) and hands the id
  ;; back only when on-start fires, so wherever on-start sits is how long
  ;; POST /v1/runs takes. It used to sit AFTER (mapv open-branch! (range
  ;; width)), and open-branch! spawns a Prolog session per branch, so the
  ;; endpoint took as long as the whole beam took to come up: measured 47ms
  ;; idle and 21095ms under load at width 1, and proportionally worse wider.
  ;;
  ;; That is what let the GUI report a failed start for a run that was already
  ;; committed and running (vf-36o). Raising the client timeout treats the
  ;; symptom; this is the cause. The docstring has always said the id comes
  ;; back "immediately", so this pins the docstring to the behaviour.
  (with-db [c]
    (let [order (atom [])
          real-open runs/open-branch!]
      (with-redefs [beam/run-rounds (fn [ctx _ _] {:status :captured :run-id (:run-id ctx)})
                    veriframe.engine.prolog/create-session (fn [_] nil)
                    runs/open-branch! (fn [conn rid m]
                                        (swap! order conj :branch)
                                        (real-open conn rid m))]
        (beam/run! {:conn c :config {:run {:share-artifacts? false}}
                    :llm-config {:provider :local :model "m"}
                    :problem "p" :max-turns 5 :beam-width 3
                    :on-start (fn [_] (swap! order conj :on-start))}))
      (is (= :on-start (first @order))
          "the caller gets the id before the first branch is opened")
      (is (= 3 (count (filter #{:branch} @order)))
          "and the branches still all open"))))

(deftest repopulation-is-a-gate-with-a-prediction-not-an-invitation
  ;; It used to append a message to the branch directly from the scheduler:
  ;; no prediction, nothing to settle, and no row in the gate tally. gen-17
  ;; sent 12 invitations in its first 120 turns and 9 were declined, which
  ;; nobody could see without counting branch-opened events by hand. That is
  ;; the same reasoning that turned branch-out from an invitation into a gate.
  ;;
  ;; The precondition needs facts only the scheduler has — how many branches
  ;; are alive, the target width, which survivor is strongest — so the
  ;; scheduler marks the chosen branch and the gate reads the mark.
  (testing "the scheduler marks rather than speaks"
    (let [b (assoc (state/new-branch {:id "B1" :problem "p"}) :status :active)
          out (#'beam/repopulate {:beam-width 3} [b] 1 7)
          marked (first out)]
      (is (= 7 (:repopulate-due marked))
          "the strongest survivor is marked for this turn")
      (is (= (count (:messages b)) (count (:messages marked)))
          "and nothing was appended to the conversation")))
  (testing "the gate fires on the mark, and settles on branch_theses"
    (let [g (get gates/by-name :repopulate)
          marked (assoc (state/new-branch {:id "B1" :problem "p"}) :repopulate-due 7)]
      (is (some? g) "there is a :repopulate gate")
      (is (true? (boolean ((:when g) {:branch marked :branch-count 1 :max-turns 40})))
          "an unacted mark fires it")
      (is (false? (boolean ((:when g) {:branch (state/new-branch {:id "B1" :problem "p"})
                                       :branch-count 1 :max-turns 40})))
          "no mark, no fire")
      (is (= :met (arbiter/settle {:gate :repopulate :turn 7 :window (:window g)}
                                  {:current-turn 8 :tools-called ["branch_theses"]}))
          "complying is met")
      (is (= :unmet (arbiter/settle {:gate :repopulate :turn 7 :window (:window g)}
                                    {:current-turn (+ 7 (:window g)) :tools-called ["verify"]}))
          "declining past the window is unmet — which is the number that was invisible")))
  (testing "it does not re-fire while the branch is still acting on it"
    (let [g (get gates/by-name :repopulate)
          acted (-> (state/new-branch {:id "B1" :problem "p"})
                    (assoc :repopulate-due 7)
                    (assoc :gate-history [{:gate :repopulate :turn 7}]))]
      (is (false? (boolean ((:when g) {:branch acted :branch-count 1 :max-turns 40})))))))

;; --- searching is not a substitute for proving ------------------------------

(deftest searching-without-attempting-anything-is-eventually-refused
  ;; vf-*, gen-27. lean_search is :neutral, so it neither counts against a
  ;; branch nor advances it, and a branch can therefore search indefinitely.
  ;; It does: 62 of that run's first 92 turns were searches, against 3
  ;; verify_lean and 5 proof_start, with consecutive runs of 11, 13 and 8
  ;; searches and no verification attempt in between.
  ;;
  ;; The harness noticed seventeen times and was ignored seventeen times —
  ;; progress-stalled 0 met to 6 unmet, prologue-cap 0 to 11. Which is
  ;; arbiter/prefill-for's finding again: gates that WITHHOLD change behaviour,
  ;; gates that SUGGEST do not. The no-good-match message added earlier also
  ;; only suggests, and a branch reads "Mathlib may not have this, prove it
  ;; directly" and searches again with different words.
  ;;
  ;; So the search is refused. A verification attempt — successful or not —
  ;; clears the count, so a branch alternating search and proof never sees it.
  (let [search-turn (fn [branch]
                      (with-redefs [lean-search/search (fn [& _] [])
                                    lean-search/render (fn [& _] "no matches")]
                        (tools/run-tool {:branch branch :turn 1
                                         :tool-name "lean_search"
                                         :args {:query "TransGen chain"}})))
        run-n (fn [n]
                (reduce (fn [b _] (:branch (search-turn b)))
                        (state/new-branch {:id "B1" :problem "p"})
                        (range n)))]

    (testing "a few searches in a row are fine"
      (let [r (search-turn (run-n 3))]
        (is (= :neutral (:category r)))
        (is (str/includes? (:result r) "no matches"))))

    (testing "a long run of them without an attempt is refused"
      (let [r (search-turn (run-n 8))]
        (is (= :mechanics (:category r))
            "nothing was claimed or tested, so this is not a verification failure")
        (is (re-find #"(?i)without" (:result r)))
        (is (re-find #"(?i)prove|verify_lean|proof_start" (:result r))
            "and it says what clears the refusal")))

    (testing "a verification attempt clears it, even a failing one"
      ;; Driven through run-turn rather than run-tool: the counter is turn
      ;; bookkeeping, like record-outcome and add-turn, and lives with them.
      (with-db [c]
        (let [rid (runs/start-run! c {:problem "p" :beam-width 1})
              stalled (run-n 8)
              call (json/write-str {:name "verify_lean"
                                    :args {:claim "every supported edge is bounded by S"
                                           :lean "theorem t : True := nope"}})]
          (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
          (is (= 8 (:searches-since-attempt stalled)) "the branch really is stalled")
          (let [after (with-redefs [llm/chat (fn [& _]
                                               {:content (str "```tool-call\n" call "\n```")
                                                :finish-reason "stop"})
                                    lean-repl/create-session (fn [& _] {:id "s"})
                                    lean-repl/mathlib-env (fn [& _] nil)
                                    lean-repl/alive? (fn [_] true)
                                    lean-pool/checkout! (fn [& _] nil)
                                    lean-repl/run-command
                                    (fn [& _] {:ok false :sorries []
                                               :errors [{:data "unknown identifier"}]})]
                        (aloop/run-turn {:conn c :run-id rid :max-turns 40
                                         :llm-adapter :a :llm-config {:max-tokens 16384}}
                                        stalled 9))]
            (is (nil? (:searches-since-attempt after))
                "a failed attempt clears it — trying is what the refusal asks for")
            (is (= :neutral (:category (search-turn after))))))))))

