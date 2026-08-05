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
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is are]]
            [veriframe.agent.arbiter :as arbiter]
            [veriframe.agent.beam :as beam]
            [veriframe.engine.prolog]
            [veriframe.agent.gates :as gates]
            [veriframe.agent.state :as state]
            [veriframe.agent.tools :as tools]
            [veriframe.agent.verdict :as verdict]
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
                                                                :max-turns 40})))))))

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
                                   :branch-before before :branch-after after}))))))

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
  (let [failing (-> (branch-with :consecutive-failures 3)
                    (assoc :turns (vec (repeat 6 {}))))
        productive (assoc failing :artifacts [{:claim "c" :claim-status :confirmed
                                               :turn 5}])]
    (is (= :culled (:status (#'beam/cull-or-keep failing 2))))
    (is (= :active (:status (#'beam/cull-or-keep productive 2))))
    (testing "a stale confirmation does not protect it forever"
      (let [stale (assoc failing :artifacts [{:claim "c" :claim-status :confirmed
                                              :turn 0}])]
        (is (= :culled (:status (#'beam/cull-or-keep stale 2))))))
    (testing "the last branch standing is never culled"
      ;; Found by the width sweep: the width-1 arm was culled at turn 9 of 12
      ;; and the run ended there, which reads as evidence against narrow beams
      ;; and is actually a rule fired outside the situation it was written for.
      (is (= :active (:status (#'beam/cull-or-keep failing 0)))))))

;; --- every gate must be able to speak ---------------------------------------

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
