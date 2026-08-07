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
            [veriframe.engine.prolog]
            [veriframe.agent.gates :as gates]
            [veriframe.agent.loop :as aloop]
            [veriframe.agent.resume :as resume]
            [veriframe.agent.state :as state]
            [veriframe.agent.tools :as tools]
            [veriframe.agent.verdict :as verdict]
            [veriframe.llm.client :as llm]
            [clojure.data.json :as json]
            [veriframe.store.artifacts :as artifacts]
            [veriframe.store.db :as db]
            [veriframe.store.failures :as failures]
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
                  :artifacts [{:claim "c" :claim-status :confirmed
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
                         :args {:claim "c" :proposedAnswer "42"}})
        (tools/run-tool {:branch b :turn 1 :conn c :run-id rid
                         :tool-name "review"
                         :args {:claim "c"
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
                  :artifacts [{:claim "c" :claim-status :confirmed
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
                               :args {:claim "c" :proposedAnswer "42"}})]
        (is (str/includes? (:result r) "PATCHES:"))
        (is (str/includes? (:result r) "bind X to a witness"))
        (is (str/includes? (get-in r [:failure :reason]) "1 patchable"))))))

(deftest judges-receive-the-exemption-list
  ;; The DO-NOT-FLAG list only works if the judge actually sees it, and the
  ;; never-exempt section is what keeps it from reading as a relaxation. Both
  ;; must reach both judges or the exemption is a file, not a mechanism.
  (let [b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim "c" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})
        prompts (atom [])]
    (with-redefs [llm/chat (fn [_ _ msgs _]
                             (swap! prompts conj
                                    (:content (last msgs)))
                             {:content "VERDICT: FAIL"})]
      (tools/run-tool {:branch b :turn 1 :tool-name "audit"
                       :args {:claim "c" :proposedAnswer "42"}})
      (tools/run-tool {:branch b :turn 1 :tool-name "review"
                       :args {:claim "c" :rationale "different encoding"}}))
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
                         :artifacts [{:claim "c" :claim-status :confirmed :turn 1}]
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
                         :artifacts [{:claim "c" :claim-status :confirmed :turn 1}])
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
    (let [b (branch-with :artifacts [{:claim "c" :claim-status :confirmed :turn 1}]
                         :gate-history [{:gate :milestone :turn 1}])]
      (is (not-any? #{:milestone} (map :gate (arbiter/eligible {:branch b
                                                                :max-turns 40}))))))

  (testing "the emergency review is guarded like every other steer"
    ;; A live knights-3 run re-fired it on three consecutive boundaries, all
    ;; predictions unmet: its precondition persists while the branch is busy
    ;; complying. It was the one steer gate with no re-fire guard.
    (let [b (branch-with :consecutive-failures 3
                         :turns (vec (repeat 4 {}))
                         :artifacts [{:claim "c" :claim-status :confirmed :turn 3}])]
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
                         :artifacts [{:claim "c" :claim-status :confirmed :turn 1}])
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
          after (branch-with :artifacts [{:claim "c" :claim-status :confirmed}])]
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
                                        [{:claim "x" :code "" :witness nil}]))))

  (testing "the witness counts as evidence, not only the claim text"
    (is (empty? (tools/uncovered-tokens "a is knave"
                                        [{:claim "solved" :code ""
                                          :witness [{:A "knave"}]}])))))

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
                  :artifacts [{:claim "c" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})]
    (with-redefs [llm/chat (fn [& _]
                             {:content (str "ESTABLISHED: the sequence converges for n = 1, 2, 3\n"
                                            "RELAXATION: yes\n"
                                            "VERDICT: PASS")})]
      (let [la (:last-audit (:branch (tools/run-tool
                                      {:branch b :turn 1 :tool-name "audit"
                                       :args {:claim "c" :proposedAnswer "42"}})))]
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
                  :artifacts [{:claim "c" :claim-status :confirmed
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
                               :args {:claim "c" :proposedAnswer "42"}})]
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
                  :artifacts [{:claim "c" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})
        calls (atom 0)]
    (with-redefs [llm/chat (fn [& _] (swap! calls inc)
                             {:content "<think>never a verdict"})]
      (let [r (tools/run-tool {:branch b :turn 1 :tool-name "audit"
                               :args {:claim "c" :proposedAnswer "42"}})]
        (is (= :failure (:category r)) "exhausted retries fail closed")
        (is (= 3 @calls) "attempts are bounded")))))

(deftest judge-transport-failure-is-not-retried
  ;; The retry exists for a judge that ANSWERED without a verdict. A transport
  ;; failure already went through llm/chat's own bounded retry loop, and a
  ;; second loop here would multiply that by three.
  (let [b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "g" :technique "t" :subClaims []}
                  :artifacts [{:claim "c" :claim-status :confirmed
                               :kind :smt :tier :confirmed :code "c1"}]})
        calls (atom 0)]
    (with-redefs [llm/chat (fn [& _] (swap! calls inc)
                             (throw (ex-info "socket reset" {})))]
      (let [r (tools/run-tool {:branch b :turn 1 :tool-name "audit"
                               :args {:claim "c" :proposedAnswer "42"}})]
        (is (= :failure (:category r)))
        (is (= 1 @calls))))))

(deftest relaxation-is-journalled-as-thesis-drift
  (let [c (db/connect ":memory:")
        _ (db/migrate! c)
        rid (runs/start-run! c {:problem "p" :beam-width 1})
        b (merge (state/new-branch {:id "B1" :problem "p"})
                 {:thesis {:goal "the sequence converges for all natural n"
                           :technique "t" :subClaims []}
                  :artifacts [{:claim "c" :claim-status :confirmed
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
                       :args {:claim "c" :proposedAnswer "42"}}))
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
           "octave_eval" "verify_octave"}
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
                                       :claim "c" :claim-status :confirmed})
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

  (testing "with no thesis registered, exploration is still credited"
    (is (state/advances-thesis? (branch-with) "anything at all"))))

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
      (are [theses] (= :failure (:category (tools/run-tool
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
        productive (assoc failing :artifacts [{:claim "c" :claim-status :confirmed
                                               :turn 5}])]
    (is (= :culled (:status (#'beam/cull-or-keep {} failing 2 []))))
    (is (= :active (:status (#'beam/cull-or-keep {} productive 2 []))))
    (testing "a stale confirmation does not protect it forever"
      (let [stale (assoc failing :artifacts [{:claim "c" :claim-status :confirmed
                                              :turn 0}])]
        (is (= :culled (:status (#'beam/cull-or-keep {} stale 2 []))))))
    (testing "the last branch standing is never culled"
      ;; Found by the width sweep: the width-1 arm was culled at turn 9 of 12
      ;; and the run ended there, which reads as evidence against narrow beams
      ;; and is actually a rule fired outside the situation it was written for.
      (is (= :active (:status (#'beam/cull-or-keep {} failing 0 [])))))))

;; --- the evolutionary loop --------------------------------------------------

(deftest branch-out-fires-on-a-confirmation-with-room-to-grow
  ;; Variation on success: a branch that just proved something is the one
  ;; worth reproducing from. Selection without reproduction is half a
  ;; genetic algorithm, and forked:0 across every live run was the symptom.
  ;; Ordering matters and is deliberate: `milestone` outranks this rung, so
  ;; the first confirmation sends the branch to review rather than straight
  ;; to forking. Result, then review, THEN widen — reproduce from a result
  ;; that survived a check, not from a raw one.
  (let [fit (branch-with :artifacts [{:claim "c" :claim-status :confirmed :turn 5}]
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
              (branch-with :artifacts [{:claim "c" :claim-status :confirmed :turn 5}]
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
      (testing "below target width, the strongest survivor is asked to reseed"
        (let [bs (#'beam/repopulate ctx [strong weak dead] 3 20)
              b1 (first (filter #(= "B1" (:id %)) bs))
              b2 (first (filter #(= "B2" (:id %)) bs))]
          (is (= 20 (:fork-invited b1)))
          (is (str/includes? (str (last (map :content (:messages b1))))
                             "branch_theses"))
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
                                  :artifacts [{:kind :prolog :claim "c"
                                               :claim-status :confirmed :tier :fast}])
          relaxed (finished-branch "B2"
                                   :last-audit {:relaxation? true}
                                   :artifacts [{:kind :prolog :claim "c"
                                                :claim-status :confirmed :tier :fast}
                                               {:kind :smt :claim "c2"
                                                :claim-status :confirmed :tier :fast}
                                               {:kind :smt :claim "c3"
                                                :claim-status :confirmed :tier :fast}])]
      (is (= ["B1" "B2"] (mapv :id (state/rank-finished [relaxed direct])))
          "non-relaxation is the first component, so it wins regardless of the rest"))))

(deftest rank-finished-slow-tier-beats-fast-only
  (testing "any slow-tier evidence outranks a fast-only branch with more artifacts"
    (let [fast (finished-branch "B1"
                                :artifacts [{:kind :prolog :claim "c"
                                             :claim-status :confirmed :tier :fast}
                                            {:kind :smt :claim "c2"
                                             :claim-status :confirmed :tier :fast}])
          slow (finished-branch "B2"
                                :tiers-seen #{:slow}
                                :artifacts [{:kind :prolog :claim "c"
                                             :claim-status :confirmed :tier :fast}])]
      (is (= ["B2" "B1"] (mapv :id (state/rank-finished [fast slow])))
          "the review/template signal is compared before artifact count"))))

(deftest rank-finished-engine-diversity-beats-count
  (testing "distinct engine kinds compare before artifact count"
    (let [two-smt (finished-branch "B1"
                                   :artifacts [{:kind :smt :claim "c"
                                                :claim-status :confirmed :tier :fast}
                                               {:kind :smt :claim "c2"
                                                :claim-status :confirmed :tier :fast}])
          smt-plus-prolog (finished-branch "B2"
                                           :artifacts [{:kind :smt :claim "c"
                                                        :claim-status :confirmed :tier :fast}
                                                       {:kind :prolog :claim "c"
                                                        :claim-status :confirmed :tier :fast}])]
      (is (= ["B2" "B1"] (mapv :id (state/rank-finished [two-smt smt-plus-prolog])))
          "one z3 + one prolog beats two z3s: diversity (component c) outranks count (component d)"))))

(deftest rank-finished-id-breaks-ties-stably
  (testing "identical evidence ranks by branch id ascending, independent of input order"
    (let [mk (fn [id] (finished-branch id
                                       :artifacts [{:kind :prolog :claim "c"
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
                                    :artifacts [{:kind :prolog :claim "c"
                                                 :claim-status :confirmed :tier :fast}])
                   (finished-branch "B2"
                                    :artifacts [{:kind :prolog :claim "c"
                                                 :claim-status :confirmed :tier :fast}
                                                {:kind :smt :claim "c2"
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
                                    :artifacts [{:kind :prolog :claim "c"
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
