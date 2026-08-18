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
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [veriframe.agent.claims :as claims]
            [veriframe.agent.faithful :as faithful]
            [veriframe.agent.gates :as gates]
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
            [veriframe.llm.message :as message]
            [veriframe.store.artifacts :as artifacts]
            [veriframe.store.journal :as journal]))

(defmulti run-tool
  (fn [ctx] (:tool-name ctx)))

(defn- ok [branch result & {:as extra}]
  (merge {:result result :category :neutral :progress? false :branch branch} extra))

(defn- fail [branch result & {:as extra}]
  (merge {:result result :category :failure :progress? false :branch branch} extra))

(defn- malformed
  "A call the harness could not act on because its arguments were wrong.

  NOT a failure. The branch produced no claim and tested nothing, so there is
  no evidence here about its line of inquiry — the same reasoning `unavailable`
  makes about an engine outage and the branch loop makes about a malformed
  fence. Charging it to the counter that decides whether a branch lives is the
  vf-jki mistake, and this is the fifth place it turned up: fences,
  expectedVerdict, proof_start, outages, and argument shape.

  Across gen-22, 23 and 24, roughly 21 of 213 failure-turns were pure argument
  shape, 16 of them `branch_theses` emitting the byte-identical complaint,
  while gen-24 culled five of eight branches with reasons like \"after 3
  consecutive failures\".

  `:mechanics` rather than `:neutral`, deliberately: the count is still kept
  and still bounds a branch looping on malformed calls, which is real spend.
  It just stops being read as mathematics."
  [branch result]
  {:result result :category :mechanics :progress? false :branch branch})

(defn- unavailable
  "An engine could not be reached. Not the branch's fault, so not its failure.

  gen-18 B3 was culled after six consecutive failures while pursuing the
  reduction to a separable convex cost flow — the strongest line in the run.
  One of the six was `Lean is unavailable`, a fact about the process pool. A
  branch cannot answer for an outage and must not spend cull budget on one, so
  this is neutral: the failure counter neither rises nor resets, and
  turns-since-progress still ticks because nothing was established."
  [branch engine e]
  (ok branch (str engine " is unavailable: " (ex-message e))))

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

(defn- missing
  "The complaint for absent required arguments, WITH the call it wanted.

  This used to be a bare list of names. gen-20 B1 called `proof_start` without
  its arguments five times — three producing the byte-identical message — and
  was culled for it; a model that did not understand the call the first time
  learns nothing from being told the same names again. The skeleton costs
  nothing and needs no schema registry, because the tool name and the keys it
  requires are exactly what this function is already handed."
  [ctx & ks]
  (let [absent (remove #(let [v (arg ctx %)]
                          (and (some? v) (not (and (string? v) (str/blank? v)))))
                       ks)]
    (when (seq absent)
      (str "Missing required argument(s): " (str/join ", " (map name absent)) "."
           "\n\nA call to `" (:tool-name ctx) "` looks like:\n"
           "```tool-call\n"
           "{\"name\": \"" (:tool-name ctx) "\", \"args\": {"
           (str/join ", " (for [k ks]
                            (str "\"" (name k) "\": \"<" (name k) ">\"")))
           "}}\n```"))))

(def ^:private assertion-markers
  "Tokens that make a sentence assert something rather than name something.

  A relation, a quantifier or a conditional is what turns a noun phrase into a
  proposition. Deliberately generous: the check exists to catch labels, not to
  police prose."
  #{"if" "then" "iff" "implies" "every" "all" "any" "each" "exists" "some"
    "no" "not" "never" "unless" "whenever" "at most" "at least" "fewer than"
    "greater" "less" "equals" "divides"})

(defn- vague-claim
  "A complaint when the claim is a LABEL rather than a statement, else nil.

  Three entries the campaign now inherits forever read `weighted sum yields
  lex min`, `coordinate bound from Q bound` and `one coordinate
  scalarization`. Each is the name of a theorem rather than the theorem, so a
  branch reading the settled-state block learns nothing and must spend a fetch
  to discover it already holds the result. Artifacts outlive the run that made
  them, so this is checked where it is still cheap to fix.

  Length alone is the wrong test — short and precise is fine, long and empty is
  not. What makes a claim usable a generation later is that it ASSERTS
  something: a relation symbol, a quantifier, or a conditional. Only short
  claims are examined, so a full sentence is never second-guessed."
  [ctx]
  (let [c (str/trim (str (arg ctx :claim)))
        low (str/lower-case c)]
    (when (and (< (count c) 45)
               (not (re-find #"[=<>≤≥≠∈⊆]" c))
               ;; A number is an assertion: "the minimum cost is 2" and "there
               ;; are 8 optimal flows" are short, precise and perfectly usable
               ;; a generation later. The first version of this check refused
               ;; them, which is the false positive the suite caught — the
               ;; labels being targeted name a result and quantify nothing.
               (not (re-find #"\d" c))
               (not (some #(str/includes? low %) assertion-markers)))
      (str "That claim names a result rather than stating one: \"" c "\"."
           " Artifacts outlive the run that made them, and a later branch —"
           " possibly in a later generation — sees only this sentence."
           " State what is true, with its hypotheses: not \"coordinate bound"
           " from Q bound\" but \"if an integer vector has sum-of-squares at"
           " most Q, every coordinate is at most D whenever D^2 >= Q\"."))))

(defn- normalize-code [c] (str/trim (str/replace (str c) #"\s+" " ")))

(defn- identical-encoding
  "A confirmed artifact from the same engine whose code is this call's code, or
  nil. Whitespace-insensitive; nothing else is normalised, because anything
  looser would stop being a certainty."
  [{:keys [conn run-id branch] :as ctx} engine]
  (let [mine (normalize-code (or (arg ctx :lean) (arg ctx :smtlib) (arg ctx :expr)))]
    (when-not (str/blank? mine)
      (first (filter #(and (= engine (str (:kind %)))
                           (= mine (normalize-code (:code %))))
                     (artifacts/recent conn run-id 200))))))

;; Defined below, with the other judge-backed checks; the same-claim question
;; has to be asked from here, before a claim is taken.
(declare judge)

(defn- already-proved
  "The confirmed artifact that already states this claim, or nil.

  The registry above keys on spelling, so it sees `|k_e| <= S` and `every
  coordinate is at most S` as two different claims. gen-22 proved the same
  coefficient-feasibility lemma twice on ONE branch — a#687 and a#689, the
  same fact with B renamed to D = E*R, 4-gram Jaccard 0.28 — and a#717's own
  claim text reads \"(re-verified on this branch)\", so the branch had the
  neighbour in its ledger and re-proved it anyway. No lexical threshold
  separates those pairs: they differ in wording exactly where they agree in
  content, and a threshold loose enough to catch them merges facts a run
  needs apart, like the injectivity and surjectivity of one map.

  So the comparison is a judgement, made by a judge. FTS supplies at most one
  candidate — the best match, not a slate, which bounds this at one model call
  and makes a miss the failure mode rather than a slate of near-misses to
  adjudicate. No candidate, no call: a run whose pool holds nothing like the
  claim pays nothing for this.

  Own-branch matches count. Excluding them is right for the context block,
  where a branch re-reading its own lemmas is noise, and wrong here, where a
  branch re-proving its own lemma is the bug.

  ONLY the same engine is refused, and this is the whole of what makes the
  check safe. Two tools must reach a proved claim freely:

  `review` re-examines ground the branch has already confirmed — its body
  reads the branch's last confirmed artifact — so refusing claims that are
  already in the pool refuses review by construction. gen-23 made 8 refusals
  in its first 197 turns and 5 were review, each costing a branch the
  independent cross-check of a result it had just verified. review has no
  engine kind and so is never refused here.

  And a DIFFERENT engine reaching the same claim is not duplicate work:
  consensus/engine-agreement counts distinct engine kinds precisely because
  independent empirical checks compose where opinions do not. Two z3 runs on
  one claim are a wasted call; z3 agreeing with Lean is the evidence the tier
  system exists to collect, and suppressing it would quietly cost the run its
  strongest form of confirmation.

  PASS means SAME. The polarity is forced by verdict/instruction's fail-closed
  rule — an unsure judge answers FAIL — and the safe reading of unsure is
  \"let it verify\", since a wrong SAME withholds a verification the run may
  need while a wrong DIFFERENT costs one engine call."
  [{:keys [conn run-id tool-name] :as ctx} claim]
  (when-let [engine (get {"verify_smt" "smt" "verify_template" "smt"
                          "verify_lean" "lean" "verify_octave" "octave"}
                         tool-name)]
   (when (and conn run-id (not (str/blank? claim)))
    (if-let [same-code (identical-encoding ctx engine)]
      ;; Settled without a judge. The claim is prose and two artifacts carrying
      ;; the same theorem can be worded differently enough for a careful judge
      ;; to call them distinct: gen-27 a#809 and a#810 are byte-identical code
      ;; on one branch, refused as different because one mentioned decidable
      ;; equality in a different clause. Identical code is the same result
      ;; whatever the sentence around it says, and checking it first cannot
      ;; false-positive.
      same-code
      (when-let [cand (first (filter #(= engine (str (:kind %)))
                                     (artifacts/similar conn run-id claim 3)))]
      (let [p (str "Two statements from a mathematics run. Decide whether they"
                   " state the SAME fact.\n\n"
                   "ALREADY PROVED:\n" (:claim cand) "\n\n"
                   "ABOUT TO BE VERIFIED:\n" claim "\n\n"
                   "Answer PASS only if the second states the same fact as the"
                   " first — the same hypotheses and the same conclusion, so"
                   " that a proof of one IS a proof of the other. Renamed"
                   " variables, reordered clauses and different notation do not"
                   " make two statements different.\n\n"
                   "Answer FAIL if they differ in any way that matters: a"
                   " different bound, a weaker or stronger hypothesis, a"
                   " converse, a special case, or two halves of one result"
                   " (injectivity and surjectivity of the same map are"
                   " DIFFERENT facts). When in doubt, answer FAIL.")]
        (when (verdict/passed? (judge ctx :same-claim p))
          cand)))))))

(defn- claim-dedup
  "Serve another branch's verification of the same claim instead of spending
  this branch's call on it. Returns a result map when the claim is owned by
  another branch, nil when this branch should run the tool itself.

  The registry is optional by design: a nil registry — no run — makes every
  tool run exactly as before. A claim another branch holds in flight is served
  as a neutral notice (do not re-verify, the verdict will land); a settled
  claim is served as its outcome, the confirmed artifact credited wholesale
  with the source branch named, the failure reason carried over without
  re-running the check."
  [{:keys [claims branch] :as ctx} claim]
  (let [answer (when claims (claims/try-claim! claims (:id branch) claim))]
    (if (and answer (not= :claimed answer))
      (let [holder (:holder answer)
              disposition (if (= :held (:status answer)) :held (:outcome answer))]
          (when (and (:conn ctx) (:run-id ctx))
            (journal/note! (:conn ctx) (:run-id ctx) :verification-dedup-hit
                           {:branch-id (:id branch)
                            :data {:claim claim :holder holder
                                   :disposition (name disposition)}}))
          (case (:status answer)
            :held
            (ok branch (str "Claim `" claim "` is already being verified by branch "
                            holder ". Do not re-verify it — that verdict will land here."))

            :done
            (if (= :confirmed (:outcome answer))
              {:branch (update branch :tiers-seen conj :slow)
               :category :success
               :progress? true
               :result (str "Claim `" claim "` was already CONFIRMED by branch " holder
                            ". Its verified artifact is credited below instead of"
                            " spending another verification on the same claim.")
               :artifact (:artifact answer)}
              {:branch (update branch :tiers-seen conj :slow)
               :category :failure
               :progress? false
               :result (str "Claim `" claim "` was already checked by branch " holder
                            " and FAILED: " (:reason answer)
                            " — carried over, not re-run.")
               :failure {:claim claim :reason (:reason answer)}})))

      ;; No exact hit. The same statement may still be in the pool under
      ;; different words, which is a judgement rather than a lookup.
      (when-let [cand (already-proved ctx claim)]
        ;; This branch holds the claim it is not going to verify. Releasing is
        ;; the point of release! — no verdict was produced here, so nothing may
        ;; settle, and a later branch that genuinely needs this key must not
        ;; find it locked by a verification that never ran.
        (when claims (claims/release! claims (:id branch) claim))
        (when (and (:conn ctx) (:run-id ctx))
          (journal/note! (:conn ctx) (:run-id ctx) :same-claim-refusal
                         {:branch-id (:id branch)
                          ;; Without the turn this cannot be joined to what it
                          ;; refused, and the question "which tool did it stop"
                          ;; had to be answered by matching claim text.
                          :turn (:turn ctx)
                          :data {:claim claim :matched (:claim cand)
                                 :holder (:branch_id cand) :artifact (:id cand)
                                 :tool (:tool-name ctx)}}))
        {:branch branch
         ;; NOT :failure. The branch reasoned its way to a true statement and
         ;; encoded it; it simply aimed at ground the run already holds. That
         ;; is a fact about the run's coverage, not about this line of
         ;; inquiry, and charging it to the counter that decides whether the
         ;; branch lives is the vf-jki mistake in a fifth place.
         :category :mechanics
         :progress? false
         ;; No :artifact. Crediting a branch with a verified result on a
         ;; judge's say-so would put a claim it never proved into the list the
         ;; audit and done gates read. It gets the neighbour's exact words and
         ;; decides for itself — it is the only party that knows what it meant.
         :result (str "That statement is already proved. Branch "
                      (:branch_id cand) " confirmed it as s#" (:id cand)
                      ", in different words:\n\n  " (:claim cand)
                      "\n\nThe engine was not run. If that is your statement,"
                      " build on it — `fetch_artifact s#" (:id cand) "` has the"
                      " encoding. If what you meant differs from it, say how it"
                      " differs and state the claim so the difference is on the"
                      " face of it; a claim that reads as a restatement will be"
                      " refused again.")}))))

(defn- verdict-disposition
  "How a claim-status settles in the registry.

  The distinction the registry has to get right, and the one with the longest
  blast radius if it does not: a settled claim stays settled for the whole
  run, so the only outcomes allowed to settle one are verdicts ABOUT THE
  CLAIM. `:confirmed` and `:refuted` are that. Everything else — a proof Lean
  rejected, an open `sorry`, an encoding that formalised a different question,
  an engine that died — is a fact about the attempt, and verify_lean already
  makes this argument for the artifact table: \"a snippet Lean rejects is a
  failed proof ATTEMPT, which says nothing about whether the claim is true.\"

  Recording one of those as a verdict would tell every later branch that a
  true claim had been checked and failed, and no branch would ever check it
  again."
  [status]
  (case status
    :confirmed :confirmed
    :refuted :failed
    :released))

(defn- settle-claim!
  "Record the verdict of a verification this branch just ran, or release the
  claim when the run never produced one. The UCLA rule lives here: a verdict —
  even a failing one — is recorded and stays; only a process failure (engine
  error, unparseable judge output) releases the claim for another branch. A
  nil registry makes this a no-op."
  [{:keys [claims branch]} claim outcome payload]
  (when claims
    (if (= :released outcome)
      (claims/release! claims (:id branch) claim)
      (claims/complete! claims (:id branch) claim outcome payload))))

(defn- sketch-diversity-refusal
  "Refuse a sketch too close to one a LIVE sibling has already banked.

  vf-eaw: gen-29 put all three branches on TARGET 1 within one turn and never
  diversified; gen-30 forked to eight branches all circling the same lemma.
  The beam pays for N branches and buys one line of attack, and the boundary
  where a plan can still change cheaply is the sketch call itself. The
  sibling's approach is quoted and the branch named, so the branch can pick
  something different.

  The claim is released like every other sketch exit: a plan was never made,
  so nothing was decided, and another branch must be able to take the claim.
  Returns nil when there is no near-duplicate, or no store to ask (a nil
  registry/no conn makes tools behave as before)."
  [{:keys [branch conn run-id] :as ctx}]
  (when (and conn run-id (seq (arg ctx :claim)))
    (let [claim (arg ctx :claim)
          threshold (gates/threshold :sketch-duplicate-threshold)
          dup (some (fn [s]
                      (when (artifacts/near-duplicate? claim (:claim s) threshold)
                        s))
                    (artifacts/sibling-sketches conn run-id (:id branch)))]
      (when dup
        (settle-claim! ctx claim :released nil)
        (malformed branch
                   (str "A live sibling, " (:branch_id dup)
                        ", has already banked a sketch for almost this same"
                        " plan:\n\n\"" (:claim dup) "\"\n\n"
                        "Two branches sketching the same line buys one attack"
                         " twice. Pick a different decomposition — a different"
                         " claim, or a different Lean structure."))))))

;; --- retrieval-before-drafting (vf-3wg) --------------------------------------
;;
;; The sketch tool used to be the only place premises mattered: a branch that
;; invented a lemma found out at elaboration, after the branch had already
;; committed to a plan built on it. Retrieval-before-drafting attacks the
;; failure at its source — the branch gets the declarations that actually say
;; what it wants to prove BEFORE it drafts, instead of concluding from an
;; empty search that nothing exists (gen-30 B1.4 invented a six-lemma
;; `splitByLoop` API after exactly that).

(def ^:private max-premises
  "How many retrieved candidates a refusal or thesis result may carry.

  Five, not ten. Each renders as a `name :: statement` line the branch pays
  context for (vf-h2v), and a Mathlib statement is typically a long binder
  run. Beyond five the marginal candidate stops adding coverage and starts
  crowding the prose the branch actually needs — and the whole point of the
  relevance floor is that a few strong hits are worth more than a long
  plausible list.

  A constant here rather than gates.edn on purpose: this is a context-budget
  number, not a harness-policy lever. gates.edn is the surface for gates the
  arbiter steers on; the premise cap is a budget the code spends directly,
  and putting it next to the code that spends it keeps the two from
  drifting."
  5)

(defn- premises-for
  "Retrieve candidate premises for `query`, or nil when there is nothing to
  serve.

  The relevance floor is load-bearing — see lean-search/render's docstring.
  The ranking keeps anything sharing a single token, so against 230k
  declarations there is always SOMETHING, and a plausible-looking list of
  irrelevant names would be actively harmful: the branch would draft from
  it. gen-25 spent fourteen searches and zero verification attempts chasing
  a match that shared two words out of eight. So only hits clearing
  `relevant?` qualify, and when none do this returns nil — an honest silence
  is the correct output, and the refusal or thesis result stands on its own.

  Also returns nil when there is no Lean engine config, when the search
  throws (missing index), or when every qualifying hit has already been
  served to this branch. Retrieval decorates the refusal and the thesis
  result; it must never break either.

  Served names are tracked on the branch under :premises-served, the same
  shape as the loop's :shared-served (what this branch was already told
  about) but a separate key: that set holds journal artifact ids, this one
  holds Mathlib declaration names, and mixing the two key spaces would let a
  name collide with an id and suppress a shared artifact. Like :shared-served
  it is branch memory — a resumed branch may be re-served once, the same
  accepted cost as a duplicate shared-artifact hit."
  [{:keys [branch config]} query]
  (when (and (get-in config [:engines :lean]) (seq query))
    (try
      (let [hits (lean-search/search (get-in config [:engines :lean])
                                     query (* max-premises 2))
            served (or (:premises-served branch) #{})
            fresh (->> hits
                       (filter lean-search/relevant?)
                       (remove (comp served :n))
                       (take max-premises)
                       vec)]
        (when (seq fresh)
          {:names (mapv :n fresh)
           :lines (mapv (fn [h]
                          (str (:k h) " " (:n h)
                               (when-not (str/blank? (:s h))
                                 (str " :: " (:s h)))))
                        fresh)}))
      (catch Throwable _
        nil))))

(defn- premises-block
  "The labelled premise block for a result, or nil when there is none.

  The label tells the branch these are candidates to draft FROM, not
  results: search hits with the statement attached — exactly what a branch
  needs to stop inventing lemmas, and nothing it may cite as settled."
  [{:keys [lines]}]
  (when (seq lines)
    (str "\n\nRetrieved candidate premises to draft FROM (Mathlib search, not"
         " results):\n  " (str/join "\n  " lines))))

(defn phase-refusal
  "The one place that owns the explore/build phase policy, consulted by the
  branch loop BEFORE run-tool dispatch. Returns a result map refusing the
  call, or nil when it may proceed.

  vf-b25: verification is withheld during :explore — DSP and Hilbert both
  separate drafting from proving, and this harness's most reliable finding is
  that a gate which WITHHOLDS changes behaviour while one which SUGGESTS does
  not (gen-27 ignored seventeen advisory nudges) — and sketch is withheld
  during :build, or a branch that cannot prove anything retreats into
  re-planning forever (the drift where gen-24, gen-26 and gen-27 each hit the
  hard lemma and spent the rest of the run re-confirming settled things).

  Both refusals are `malformed` (:mechanics), deliberately: a branch refused
  by harness policy has failed at nothing, and charging it to the cull
  counter would be the vf-jki mistake. The mechanics count still bounds a
  branch looping on refusals.

  Every refusal carries `:policy-refusal? true`, so the cull record can tell
  a declined call from a malformed fence and the reason string stays true."
  [{:keys [branch tool-name] :as ctx}]
  (let [refusal (cond
                  ;; lean-verification-tools, NOT verification-tools: the way
                  ;; out of explore is a Lean sketch, so withholding Prolog,
                  ;; Z3 or Octave would demand a move a non-Lean problem
                  ;; cannot make (vf-2vi).
                  (and (= :explore (:phase branch))
                       (contains? state/lean-verification-tools tool-name))
                  ;; vf-3wg: the branch has just stated, precisely, what it
                  ;; wants to prove — the highest-signal moment in the run to
                  ;; hand it premises. The refusal carries them when retrieval
                  ;; finds something above the relevance floor, and stands on
                  ;; its own text when it does not (premises-for returns nil).
                  (let [prem (premises-for ctx (arg ctx :claim))]
                    (cond-> (malformed branch
                                       (str "You are in the EXPLORE phase: no claim reaches LEAN"
                                            " until the branch has a plan on record. Sketch the"
                                            " approach as a Lean skeleton — `sketch({claim, lean})` —"
                                            " and use `lean_search` to find the lemmas it will cite."
                                            " The phase ends when a sketch elaborates, or when the"
                                            " explore budget runs out. The other engines are open"
                                            " meanwhile: Prolog, Z3 and Octave are not withheld,"
                                            " because a Lean skeleton cannot stand in for them."
                                            (premises-block prem)))
                      (seq (:names prem))
                      (update :branch update :premises-served
                              (fnil into #{}) (:names prem))))

                  (and (= :build (:phase branch))
                       (= "sketch" tool-name))
                  (malformed branch
                             (str "You are in the BUILD phase: the plan is already"
                                  " committed, so `sketch` is refused. The way forward is to"
                                  " close its goals — `proof_start` on a statement, or"
                                  " `verify_lean` once the whole proof is ready."))

                  (= "sketch" tool-name)
                  (sketch-diversity-refusal ctx)

                  :else nil)]
    (cond-> refusal
      refusal (assoc :policy-refusal? true))))

(declare judge)

;; The done gate's lexical check, defined further down beside the rest of that
;; gate. `audit` reaches back for it: the words it flags are advice for a model
;; to weigh, not grounds to refuse a ship (vf-kpn).
(declare number-token? uncovered-tokens)

(defn- faithfulness-prompt
  "Ask whether `code` actually formalises `claim`, given what the engine said.

  `engine` names the artifact for the reader (\"an SMT-LIB encoding\"),
  `outcome` describes what came back (\"Z3 returned unsat\"), and `direction`
  is whether the harness read that outcome as confirming, refuting, or — for
  `measure`, the one tool that decides nothing — merely measuring. The
  question is the same in every case — is this a faithful formalisation — but
  what rides on the answer is not, and saying which is at stake stops the
  reviewer from grading a refutation as though a PASS were an endorsement of
  the claim."
  [claim code {:keys [engine outcome extra direction]}]
  (let [refuting? (= :refutes direction)
        measuring? (= :measures direction)]
    (str "A verifier was asked to substantiate this CLAIM by writing " engine "."
         " " outcome ", "
         (cond
           measuring?
           (str "which the harness has recorded as a MEASUREMENT: evidence about"
                " this computation at these parameters, and not a decision"
                " about anything.")

           refuting?
           (str "which is the opposite of what the author predicted, so the"
                " harness has recorded the claim as REFUTED.")

           :else "which is the outcome the author predicted.")
         " Your ONE job is to decide whether the artifact actually formalises"
         " the claim.\n\n"
         "CLAIM:\n" claim "\n\nARTIFACT:\n" code "\n\n"
         (cond
           measuring?
           (str "Answer PASS only if the claim describes exactly what THIS"
                " artifact computed, at the parameters it actually used.")

           refuting?
           (str "Answer PASS only if this outcome on THIS artifact refutes"
                " exactly the claim above — that is, only if the artifact is a"
                " faithful formalisation, so that what failed here is the claim"
                " and not something else wearing its words. Generality is NOT a"
                " licence here, and the polarity is the reverse of a proof: a"
                " witness has to satisfy every hypothesis the claim makes, so"
                " an encoding that dropped one may have found a counterexample"
                " the claim never covered, which refutes nothing.")

           :else
           (str "Answer PASS if this outcome on THIS artifact establishes the"
                " claim above — either exactly, or as a special case of"
                " something MORE GENERAL that the artifact proves. Proving a"
                " universal statement by refuting its negation, an encoding"
                " that drops a hypothesis, widens a domain, or leaves a sort"
                " unconstrained proves a HARDER theorem, and the claim follows"
                " from it: that is a PASS, not a defect. The failure runs the"
                " other way — an encoding carrying extra hypotheses the claim"
                " does not make, a narrowed domain, or constants hardcoded"
                " where the claim quantifies, proves something weaker than the"
                " claim and establishes nothing about it. That is a FAIL."))
         " Answer FAIL if the artifact expresses something"
         " weaker, or simply different — in particular check that every"
         " coefficient, bound, threshold and index set matches what the claim"
         " says, that the quantity being summed or compared is the one named, and"
         " that no case the claim covers is missing."
         (when extra (str "\n\n" extra))
         "\n\nDo not re-derive the mathematics or re-check the engine's work."
         " Only compare the claim to the formalisation. Judge the artifact as"
         " written: if it does not match the claim, answer FAIL. It is not an"
         " option to reinterpret the claim so that it fits.")))

(def ^:private prolog-faithfulness-note
  "Prolog earns a specific warning because the failure that motivated this
  check was invisible without it: a goal can succeed while enforcing nothing.

  gen-13 confirmed a false claim whose rule posted every constraint inside
  findall/3, which runs its goal in a separate context and discards bindings
  AND constraint posts on completion. What survived was `Row ins 0..1,
  sum(Row,#=,1)` — pick one value per row — which any assignment satisfies.
  The goal returned instantly with empty witnesses and the harness read that
  as proof."
  (str "This is Prolog, so check that the goal actually ENFORCES what the"
       " claim asserts rather than merely succeeding. Constraints posted"
       " inside findall/3, forall/2 or \\+ are undone when those complete, so"
       " a goal that posts its real constraints there and then labels is"
       " solving an unconstrained problem. A goal that succeeds instantly with"
       " empty or unbound witnesses is the signature. Also check that the"
       " rules shown actually define every predicate the claim depends on."))

(def ^:private octave-faithfulness-note
  "Octave is the one engine that computes rather than decides, so the gap
  between artifact and claim is wider here than anywhere else: a claim about
  all reals cannot be reached from any number of evaluated points, and a
  tolerance turns a strict statement into an approximate one."
  (str "This is Octave, which COMPUTES rather than decides: it says what"
       " happened for these inputs at this precision. Check that the expression"
       " actually reads the values the workspace computed rather than numbers"
       " transcribed into it, that a tolerance is not standing in for a strict"
       " inequality the claim states, and above all that the claim is about"
       " these inputs. A claim quantified over all reals, all integers, or an"
       " infinite family is NOT established by a finite computation, however"
       " many cases it covers — that is FAIL no matter how clean the code is."))

(def ^:private measurement-faithfulness-note
  "`measure` records a number rather than a verdict, which removes the usual
  question — did the engine decide the right thing — and leaves only the one
  that is left: is the claim about what was computed. The generalisation trap
  is worse here than for `verify_octave`, because a measurement invites a
  sentence about the phenomenon rather than about the run."
  (str "Nothing was DECIDED here: Octave computed a value and the harness"
       " recorded it, so the only question is whether the claim describes that"
       " computation. Check that every parameter the claim names — sizes,"
       " counts, trial numbers, the point it was measured at — is one the code"
       " actually used, and that the claim stops where the run stopped. A claim"
       " that generalises past the parameters swept, to all n or to the limit"
       " or to an infinite family, is FAIL: what was measured is what was run."
       " A claim reporting a trend the numbers do not show is also FAIL."))

(def ^:private lean-faithfulness-note
  "Lean's proof checking is not in question; its statements are. Everything
  that can go wrong here goes wrong in the theorem line."
  (str "Lean has already checked the PROOF, so do not re-examine it. The only"
       " question is whether the STATEMENT is the claim. Check the quantifiers"
       " and their ranges, the hypotheses (an unused or contradictory one makes"
       " a theorem elaborate while proving nothing about the claim), the"
       " direction of every inequality, and whether a definition introduced in"
       " the snippet means what the claim says it means — a `def` that is"
       " subtly the wrong object makes a true theorem about the wrong thing."
       "\n\nTwo failures specifically, both of which have shipped as results"
       " here:\n\n"
       "1. THE CONCLUSION IS THE HYPOTHESES HANDED BACK. If the goal can be"
       " discharged by supplying the hypotheses as the witness — `exact ⟨h1,"
       " h2, …⟩` for an existential whose components are exactly what was"
       " assumed — the theorem assumes what the claim says it establishes."
       " One artifact assumed a list WAS a simple cycle and concluded that a"
       " simple cycle exists. Answer FAIL: the work the claim describes is the"
       " step that was assumed away.\n\n"
       "2. THE CLAIM NAMES OBJECTS THE STATEMENT DOES NOT CONTAIN. Read the"
       " claim's nouns and find each one in the theorem. A claim describing a"
       " subdivided network of parallel unit arcs, matched against a statement"
       " with no arcs and no capacities in it, is not that claim — it was a"
       " Mathlib identity about list lengths wearing the claim's description."
       " Answer FAIL when the statement is silent about the construction the"
       " claim is about, however true the statement is."))

(def ^:private template-faithfulness-note
  "verify_template cross-checks two encodings, which the tool used to treat as
  making review unnecessary. It does not: both encodings are built from the
  same model-supplied slots, so they agree with each other exactly when the
  instantiation was consistent, and say nothing about whether the values are
  the ones the claim names."
  (str "This encoding came from a vetted template, so the template's logic is"
       " not in question and neither is the cross-check — both encodings were"
       " built from the SAME slot values, so their agreement shows the"
       " instantiation was consistent and nothing more. What is in question is"
       " the slots: check that every value filled in is the one the claim"
       " names, in the units and at the scale the claim uses."))

(defn- prolog-artifact-code
  "The goal together with the rules it runs against.

  A Prolog goal is a name; its meaning lives in rules asserted on earlier
  turns. Recording the goal alone — which is what the artifact used to hold —
  leaves a row that neither a judge nor a person can audit, and that is how a
  false claim survived review."
  [session check]
  (let [rules (seq (remove str/blank? (map :code (prolog/snapshot session))))]
    (if rules
      (str (str/join "\n" rules) "\n\n% goal:\n" check)
      check)))

(defn- encoding-faithful?
  "Whether the SMT-LIB actually formalises the claim it is offered for.

  Nothing else in the pipeline asks this. `expectedVerdict` pins which verdict
  supports the claim, and the free-variable check catches a SAT mistaken for a
  witness, but both take the encoding on trust — so a formula that is simply
  about something else confirms whatever English is stapled to it. Three
  gen-11 artifacts shipped that way: the claim named the mod-3 layered
  condition, the encoding gave 3-divisible moduli coefficient L/m where that
  condition requires 3L/m, and Z3's UNSAT was real while the claim was false.

  Asked of refutations too. This used to be confirmations only, on the
  reasoning that a refuted artifact substantiates nothing — which is false. A
  refutation asserts the negation, and the negation is a substantive claim
  that the branch believes, the claims registry hands to other branches as
  settled, and nobody re-runs. gen-13 refuted `the mod-15 condition for P_3000
  is satisfiable` from an encoding that pinned y0 = 0; the claim quantified
  over every y0, so the unsat was about a different question, and the run
  steered on it for turns. Ambiguous outcomes are still not judged: there is
  no assertion in them to be unfaithful to."
  [ctx claim code {:keys [structural] :as opts}]
  (let [{:keys [ok warnings]} (or structural {:ok true})]
    (if-not ok
      ;; A deterministic objection settles it. These fire on arithmetic or
      ;; syntax, never on judgement, so there is nothing for a reviewer to
      ;; weigh — and asking anyway invites it to talk the objection away.
      (do (when (and (:conn ctx) (:run-id ctx))
            (journal/note! (:conn ctx) (:run-id ctx) :structural-objection
                           {:branch-id (:id (:branch ctx)) :turn (:turn ctx)
                            :data {:claim claim :warnings warnings}}))
          {:ok? false :reason (str/join " " warnings)})
      ;; The REASON travels with the verdict. It used to be dropped here and
      ;; the caller told the branch to go and look again, which is not the
      ;; same information: a reviewer that says "the vertex-6 divergence
      ;; equation has the wrong sign, change (+ (- k4) (- k7)) to
      ;; (+ k4 (- k7))" has done the work, and throwing that away had a
      ;; branch resubmit the identical artifact on the next turn.
      (let [j (judge ctx :faithfulness (faithfulness-prompt claim code opts))]
        {:ok? (verdict/passed? j)
         :reason (or (not-empty (str/trim (str (:text j))))
                     (:reason j))}))))


;; --- Prolog -----------------------------------------------------------------

(defmethod run-tool "add_rule" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :code)]
    (malformed branch m)
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
    (malformed branch m)
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
  (if-let [m (or (missing ctx :claim :check) (vague-claim ctx))]
    (malformed branch m)
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
              code (prolog-artifact-code (:prolog branch) (arg ctx :check))
              ;; Hoisted out of the opts map so the objection can be quoted
              ;; back to the branch. It used to be journalled and nowhere
              ;; else, which is how a branch repeated the identical defect on
              ;; the turn after being caught: it was told the goal succeeded.
              structural (faithful/check-prolog claim code)
              prolog-review
              (delay (encoding-faithful?
                      ctx claim code
                      {:engine "a Prolog program and a goal to run against it"
                       :outcome "The goal succeeded"
                       :extra prolog-faithfulness-note
                       :structural structural}))
              status (cond
                       all-unbound? :existential
                       ;; A ground goal reports {} bindings, which is neither
                       ;; bound nor all-unbound, so it used to reach :confirmed
                       ;; on the strength of having succeeded at all — with the
                       ;; rules that give it meaning nowhere in the artifact.
                       (not (:ok? @prolog-review)) :unfaithful
                       :else :confirmed)
              unfaithful? (= :unfaithful status)
              objection (when unfaithful? (:reason @prolog-review))]
          (cond->
           {:branch branch
           :category (if (or all-unbound? unfaithful?) :failure :success)
           :progress? (not (or all-unbound? unfaithful?))
           :result (str "The goal succeeded with " (count answers)
                        (if (:truncated reply) "+ (truncated)" "") " solution(s):\n"
                        (str/join "\n" (map #(str "  " (:formatted %)) shown))
                        (cond
                          all-unbound?
                          (str "\n\nEvery variable came back unbound, so this says the goal"
                               " is satisfiable and nothing about which assignment holds."
                               " It does NOT confirm your claim. Bind the variables the"
                               " claim is about, or query them directly.")

                          unfaithful?
                          (str "\n\nBut the goal SUCCEEDING is not the claim being"
                               " established, and this artifact does not establish it."
                               (if (not-empty objection)
                                 (str " " objection)
                                 (str " Review read the program beside the claim and"
                                      " found it does not formalise it. Check that every"
                                      " predicate the claim depends on is defined here and"
                                      " that the goal enforces what the claim asserts."))
                               "\n\nFix the program and re-run. Restating the claim to"
                               " match a goal that enforces nothing is not a fix.")

                          (seq some-unbound)
                          (str "\n\nNote: " (str/join ", " (map name some-unbound))
                               " came back unbound, so the claim rests on the variables"
                               " that did bind.")
                          :else ""))
           :artifact {:kind :prolog :claim claim :code code
                      :claim-status status :tier :fast :witness bindings}}

           ;; The failure log crosses branches; this path never wrote to it on
           ;; an unfaithful outcome, so the objection reached the branch that
           ;; earned it and no other. Half a fix is how a sibling repeats a
           ;; lesson twenty turns later.
           unfaithful?
           (assoc :failure {:claim claim :reason objection})))))))

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

;; `judge` lives further down with the other sub-LLM machinery it needs.
;; Forward-declared rather than hoisted above this point: the alternative is
;; moving judge, its retry loop and its budget sizing over three hundred lines
;; up the file to satisfy the reader's eye, which is a much larger diff than
;; the coupling deserves.
(defmethod run-tool "verify_smt" [{:keys [branch config] :as ctx}]
  (if-let [m (or (missing ctx :claim :smtlib) (vague-claim ctx))]
    (malformed branch m)
    (if-let [served (claim-dedup ctx (arg ctx :claim))]
      served
      (let [claim (arg ctx :claim)
          smtlib (arg ctx :smtlib)
          expected (some-> (arg ctx :expectedVerdict) str/lower-case keyword)
          r (smt/run-smt smtlib (get-in config [:engines :z3]))]
      (if (= :error (:status r))
        (do (settle-claim! ctx claim :released nil)
            (fail branch (:error r) :failure {:claim claim :reason (:error r)}))
        (let [free (free-variables smtlib)
              status (smt-claim-status (:verdict r) expected (seq free))
              review (fn [dir] (encoding-faithful?
                                ctx claim smtlib
                                {:engine "an SMT-LIB encoding"
                                 :outcome (str "Z3 returned " (name (:verdict r)))
                                 :direction dir
                                 :structural (faithful/check-smt claim smtlib
                                                                 {:verdict (:verdict r)})}))
              verdict-review (case status
                               :confirmed (review :confirms)
                               ;; A refutation is an assertion too — of the
                               ;; negation — and it settles the claim for every
                               ;; other branch.
                               :refuted (review :refutes)
                               nil)
              status (cond
                       (nil? verdict-review) status
                       (:ok? verdict-review) status
                       :else :unfaithful)
              objection (when (= :unfaithful status) (:reason verdict-review))
              artifact {:kind :smt :claim claim :code smtlib :verdict (:verdict r)
                        :witness (:model r) :claim-status status :tier :fast}]
          (settle-claim! ctx claim (verdict-disposition status)
                         (if (= :confirmed status)
                           artifact
                           (or objection (str "z3 returned " (name (:verdict r))))))
          (merge
           {:branch branch
            ;; An UNDECLARED expectedVerdict is an annotation fault, not a
            ;; failed verification: the engine ran and answered, and nothing
            ;; about the branch's reasoning failed — it just never said which
            ;; verdict would support the claim. Charging it to the cull
            ;; counter is the vf-jki mistake in a different tool, and it cost
            ;; gen-21 the branch that had assembled the whole selector.
            ;;
            ;; Z3 answering UNKNOWN stays a failure: there the engine could
            ;; not decide, which is a fact about the encoding the branch chose.
            :category (cond
                        (= :confirmed status) :success
                        (and (= :ambiguous status) (nil? expected)) :mechanics
                        :else :failure)
            :progress? (= :confirmed status)
            :result (str "Z3 says " (name (:verdict r)) ". "
                         (case status
                           :confirmed "That matches your expectedVerdict — claim CONFIRMED."
                           :refuted "That contradicts your expectedVerdict — claim REFUTED."
                           :unfaithful
                           (str "The encoding does not formalise the claim, so"
                                " this verdict does not settle it either way."
                                " Z3 answered the question you asked; the"
                                " question was not the one you stated.\n\n"
                                objection
                                "\n\nFix the encoding and re-run. Narrowing the"
                                " claim until it matches a formula you have"
                                " already written is not a fix: state the claim"
                                " you mean, then encode that.")
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
            :artifact artifact}
           ;; The failure log is what crosses branches, so it carries the
           ;; objection rather than a description of the engine's output.
           ;; "z3 returned unsat (status unfaithful)" taught the next branch
           ;; nothing, and it made the same mistake.
           (when-not (= :confirmed status)
             {:failure {:claim claim
                        :reason (or objection
                                    (str "z3 returned " (name (:verdict r))
                                         " (status " (name status) ")"))}}))))))))

(defmethod run-tool "verify_template" [{:keys [branch config] :as ctx}]
  (if-let [m (or (missing ctx :claim :template) (vague-claim ctx))]
    (malformed branch m)
    (if-let [served (claim-dedup ctx (arg ctx :claim))]
      served
      (let [claim (arg ctx :claim)
            tname (arg ctx :template)
            slots (or (arg ctx :slots) {})
            r (smt/run-template tname slots (get-in config [:engines :z3]))]
        (if (= :error (:status r))
          ;; The engine never ran — a process failure, so the claim is released
          ;; for another branch to try rather than left locked behind an error.
          (do (settle-claim! ctx claim :released nil)
              (fail branch (:error r) :failure {:claim claim :reason (:error r)}))
          (let [smtlib (get-in r [:primary :smtlib])
                verdict (get-in r [:primary :verdict])
                status (cond (:confirmed r) :confirmed
                             (:agreed r) :refuted
                             :else :ambiguous)
                ;; The cross-check used to stand in for review. It cannot:
                ;; both encodings are built from the same slots, so agreement
                ;; means the instantiation was consistent, not that the values
                ;; are the claim's.
                treview (when (= :confirmed status)
                          (encoding-faithful?
                                      ctx claim smtlib
                                      {:engine (str "an SMT-LIB encoding generated"
                                                    " from the `" tname "` template")
                                       :outcome (str "Z3 returned "
                                                     (name verdict)
                                                     " and the cross-check agreed")
                                       :direction :confirms
                                       :extra template-faithfulness-note
                                       :structural (faithful/check-smt
                                                    claim smtlib {:verdict verdict})}))
                status (if (and treview (not (:ok? treview))) :unfaithful status)
                objection (when (= :unfaithful status) (:reason treview))
                confirmed? (= :confirmed status)
                artifact {:kind :smt :claim claim
                          :code smtlib
                          :verdict verdict
                          :witness (get-in r [:primary :model])
                          :claim-status status :tier :slow}]
            ;; Not `(if confirmed? :confirmed :failed)`. An :unfaithful status
            ;; means the template was filled to ask a different question, which
            ;; settles nothing about the claim — recording it as a verdict shut
            ;; the claim for the rest of the run against every branch that
            ;; could have filled the template correctly.
            (settle-claim! ctx claim (verdict-disposition status)
                           (if confirmed? artifact (:note r)))
            (merge
             {:branch (update branch :tiers-seen conj :slow)
              :category (if confirmed? :success :failure)
              :progress? confirmed?
              :result (str (:note r)
                           "\n  primary   " (name (get-in r [:primary :verdict]))
                           " (expected " (name (get-in r [:primary :expected])) ")"
                           "\n  crosscheck " (name (get-in r [:cross :verdict]))
                           " (expected " (name (get-in r [:cross :expected])) ")"
                           (when confirmed?
                             (str "\n\nBoth encodings agree and the slots match the"
                                  " claim, so this needs no separate review."))
                           (when (= :unfaithful status)
                             (str "\n\nBoth encodings agree — but they were built from"
                                  " the same slots, and review found those slots do"
                                  " not say what the claim says. The template did its"
                                  " job on the numbers you gave it.\n\n" objection
                                  "\n\nCheck each slot against the claim's wording"
                                  " and re-run.")))
              :artifact artifact}
             (when-not confirmed?
               {:failure {:claim claim
                          :reason (if (= :unfaithful status)
                                    objection
                                    (:note r))}}))))))))

;; --- planning ---------------------------------------------------------------

(defmethod run-tool "thesis" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :goal :technique)]
    (malformed branch m)
    (let [thesis {:goal (arg ctx :goal)
                  :subClaims (vec (or (arg ctx :subClaims) []))
                  :technique (arg ctx :technique)
                  :nonFiniteJustification (arg ctx :nonFiniteJustification)
                  :set-at-turn (:turn ctx)}
          ;; vf-3wg, retrieve-before-drafting in its purest form: the branch
          ;; has declared what it will work on and drafted nothing yet.
          prem (premises-for ctx (arg ctx :goal))]
      (ok (cond-> (assoc branch :thesis thesis)
            (seq (:names prem))
            (update :premises-served (fnil into #{}) (:names prem)))
          (str "Thesis registered: " (:goal thesis)
               "\nTechnique: " (:technique thesis)
               (when (seq (:subClaims thesis))
                 (str "\nSub-claims:\n"
                      (str/join "\n" (map-indexed #(str "  " (inc %1) ". " %2)
                                                  (:subClaims thesis)))))
               "\n\nThe audit gate cross-references this against what you actually"
               " verified, so a general claim backed only by small instances will"
               " be caught here."
               (premises-block prem))
          :progress? true
          :thesis thesis))))

;; --- sub-LLM judgements -----------------------------------------------------

(def ^:private judge-exemptions
  "DO-NOT-FLAG list for the audit and review judges (UCLA FirstProof finding:
  a judge with no exemption list drowns real gaps in nitpicks and burns the
  done-block budget on non-gaps). Loaded once at namespace load, like the
  other prompt files."
  (slurp (io/resource "prompts/judge-exemptions.md")))

(def ^:private max-judge-attempts 3)

(def ^:private rational-literal
  "A rational written out, capturing the denominator: `1/5`, `83/496125`."
  #"\d+\s*/\s*(\d+)")

(def ^:private wide-denominator-digits
  "A denominator this long is a certificate, not prose. `4759/4725` is the
  output of exact arithmetic; `1/2` is someone talking about a half."
  4)

(def ^:private many-rationals
  "Enough small fractions in one prompt to amount to the same work."
  8)

(def ^:private judge-token-ceiling-multiple
  "The hard cap on judge escalation, as a multiple of the configured budget.
  Escalation is a ceiling the model may not need, but every provider has a
  real limit and blowing it turns a slow call into a failed one."
  4)

(defn arithmetic-heavy?
  "Does this judge prompt invite exact-rational checking?

  Measured over the 69 audit and review calls of gen-10 of the covering
  campaign: the calls that spent their whole budget reasoning and returned no
  verdict carried a mean of 16.8 rational literals, 2.8 of them with
  four-or-more-digit denominators; the calls that answered first time carried
  5.2 and 0.7. Either signal alone catches ~76% of the expensive calls.

  Deliberately loose, because the two errors are not symmetric: `max-tokens`
  is a ceiling and not a spend, so marking a cheap prompt heavy costs nothing
  the model does not choose to use, while missing an expensive one costs a
  whole wasted call plus the retry."
  [prompt]
  (let [ms (re-seq rational-literal (str prompt))]
    (boolean (or (some #(>= (count (second %)) wide-denominator-digits) ms)
                 (>= (count ms) many-rationals)))))

(defn judge-max-tokens
  "The token budget for judge `attempt` on `prompt`, or nil to leave the
  provider default alone when nothing is configured.

  An arithmetic-heavy prompt opens at the budget a retry would have escalated
  to, since it is going to need it either way. Retries then escalate from
  wherever they opened — starting high must not turn the retry into a repeat
  of the call that just failed — up to the ceiling."
  [llm-config prompt attempt]
  (when-let [base (:max-tokens llm-config)]
    (let [opening (if (arithmetic-heavy? prompt) 2 1)
          escalation (bit-shift-left 1 (max 0 (dec attempt)))]
      (min (* base judge-token-ceiling-multiple)
           (* base opening escalation)))))

(def ^:private judge-reasoning-limit
  "How much of the judge's answer to keep in the journal.

  Enough to see which gap it named and how it argued; bounded because a
  reasoning model will happily write ten thousand characters and the journal
  is read by people and by grep, not by a model."
  4000)

(defn- note-verdict!
  "Record what the judge answered and why.

  Without this a rejection is a bare `claim-status = unfaithful` and there is
  no way to tell a caught defect from a misfire. That question — is the gate
  rejecting real work — is the one that decides whether the gate is worth
  having, and it was unanswerable the first time it came up: gen-14's mod-105
  slack computation was rejected with its arithmetic independently confirmed
  correct, and the record held nothing to diagnose it with.

  The reasoning stream is dropped, as everywhere else: the parser ignores it
  and it is the bulk of the response."
  [{:keys [conn run-id branch turn]} label prompt parsed content]
  (when (and conn run-id)
    (journal/note! conn run-id :judge-verdict
                   {:branch-id (:id branch) :turn turn
                    :data {:label (name label)
                           :verdict (name (:verdict parsed))
                           :reason (:reason parsed)
                           :gaps (:gaps parsed)
                           :prompt-chars (count (str prompt))
                           :answer (let [t (verdict/strip-reasoning content)]
                                     (subs t 0 (min judge-reasoning-limit
                                                    (count t))))}})))

(defn- judge
  "Ask the model a yes-or-no question and read the answer through the
  constrained parser. Any failure to answer cleanly fails closed.

  A well-formed response with no verdict in it — a reasoning judge that spent
  its whole token budget thinking — is a judge process failure, not evidence
  about the claim, so it is retried here inside the same tool call rather
  than handed to the branch as a failed turn: three audits died this way in
  one live run and cost the branch its ship (vf-42e). Retries sharpen the
  instruction and double the token budget, and each one is journaled. A
  transport failure is NOT retried: llm/chat already ran its own bounded
  retry loop, and a second loop here would multiply it.

  `label` names which question was asked, because three callers share this
  and a journal line saying only FAIL does not say what failed."
  ([ctx prompt] (judge ctx :unlabelled prompt))
  ([{:keys [llm-adapter llm-config conn run-id branch turn] :as ctx} label prompt]
   (loop [attempt 1]
    (let [system (str "You are a strict reviewer. " verdict/instruction
                      (when (> attempt 1)
                        (str " Your previous response ended before any verdict"
                             " line. State the verdict line now and keep any"
                             " justification to a few sentences.")))
          budget (judge-max-tokens llm-config prompt attempt)
          r (try
              {:response (llm/chat llm-adapter llm-config
                                   [{:role "system" :content system}
                                    {:role "user" :content prompt}]
                                   (cond-> {:temperature 0.0}
                                     budget (assoc :max-tokens budget)))}
              (catch Throwable e {:error (ex-message e)}))]
      (if (:error r)
        (let [parsed {:verdict :unparseable
                      :reason (str "the judge call failed: " (:error r))}]
          ;; Journalled too. A gate that fails closed on a transport error
          ;; looks from the artifact table exactly like one that rejected on
          ;; the merits, and those need telling apart.
          (note-verdict! ctx label prompt parsed "")
          parsed)
        (let [content (get-in r [:response :content])
              parsed (verdict/parse content)]
          (if (and (= :unparseable (:verdict parsed))
                   (< attempt max-judge-attempts))
            (do (when (and conn run-id)
                  (journal/note! conn run-id :judge-retry
                                 {:branch-id (:id branch) :turn turn
                                  :data {:attempt attempt
                                         :budget budget
                                         :reason (:reason parsed)}}))
                (recur (inc attempt)))
            (do
              (note-verdict! ctx label prompt parsed content)
              ;; The reasoning stream is stripped HERE, not at the call sites,
              ;; because every caller quotes :text back into the branch's message
              ;; history and a branch that reads reviewer-voice reasoning answers
              ;; its next turn as a reviewer instead of calling a tool. Keeping
              ;; the raw text in the map would leave that trap set for the next
              ;; caller added.
              (assoc parsed :text (verdict/strip-reasoning content))))))))))

(defmethod run-tool "review" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :claim :rationale)]
    (malformed branch m)
    (if-let [served (claim-dedup ctx (arg ctx :claim))]
      served
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
                       " could be wrong."
                       "\n\n" judge-exemptions)
                j (judge ctx :review p)
                passed (verdict/passed? j)
                _ (when-let [d (:disagreement j)]
                    (when (and (:conn ctx) (:run-id ctx))
                      (journal/note! (:conn ctx) (:run-id ctx)
                                     :verdict-gap-disagreement
                                     {:branch-id (:id branch)
                                      :data {:tool "review"
                                             :disagreement (name d)}})))
                _ (if (= :unparseable (:verdict j))
                    ;; The judge never produced a verdict — a process failure,
                    ;; so the claim is released for another branch to try.
                    (settle-claim! ctx claim :released nil)
                    (settle-claim! ctx claim (if passed :confirmed :failed)
                                   (if passed
                                     artifact
                                     (str "review returned " (name (:verdict j))
                                          (patchable-suffix (:minors j))))))]
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
                                       (patchable-suffix (:minors j)))}}))))))))

(defmethod run-tool "audit" [{:keys [branch] :as ctx}]
  (cond
    (missing ctx :claim :proposedAnswer)
    (malformed branch (missing ctx :claim :proposedAnswer))

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
          ;; The lexical check, handed over rather than enforced. It cannot
          ;; tell an unsupported assertion from ordinary English, and the
          ;; done gate refusing on it stranded eleven audited answers in one
          ;; run (vf-kpn). A model reading the answer in context can tell,
          ;; and this is where that reading happens.
          flagged (remove number-token?
                          (uncovered-tokens answer
                                            (concat confirmed
                                                    (state/empirical-artifacts branch))
                                            [(:problem branch)]))
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
                 (when (seq flagged)
                   (str "\n\nThese words appear in the proposed answer and in no"
                        " artifact: " (str/join ", " (map #(str "`" % "`") (take 12 flagged)))
                        ".\nThat is a lexical observation, not a finding — most of"
                        " them will be ordinary prose. Check each one: does it"
                        " carry an assertion the evidence does not support, or is"
                        " it framing, or a term from the problem statement the"
                        " answer is entitled to use in saying what it did NOT"
                        " settle? Only the first is a gap."))
                 "\n\nAnswer FAIL if the artifacts verify only instances of a claim"
                 " stated universally, if the proposed answer asserts anything no"
                 " artifact covers, or if the thesis and the evidence are about"
                 " different things. Answer PASS only if the evidence establishes"
                 " the answer as stated."
                 "\n\nThen declare what the evidence actually establishes. On a line"
                 " reading `ESTABLISHED: <text>`, restate it fully self-contained —"
                 " no anaphora. Never write \"the claim\", \"the problem above\", or"
                 " \"as stated\": the ESTABLISHED line must stand alone with the"
                 " THESIS goal and the artifacts both in the room."
                 "\nOn a line reading `RELAXATION: yes` or `RELAXATION: no`, declare"
                 " whether that ESTABLISHED claim is strictly weaker than the THESIS"
                 " goal: fewer cases, extra hypotheses, a weaker bound, existence"
                 " where a witness was asked. Evidence that fails to establish the"
                 " full goal is a FAIL with gaps, not a relaxation — RELAXATION: yes"
                 " is only for evidence that does establish something, just something"
                 " weaker."
                 "\n\n" judge-exemptions)
          j (judge ctx :audit p)
          passed (verdict/passed? j)
          _ (when-let [d (:disagreement j)]
              (when (and (:conn ctx) (:run-id ctx))
                (journal/note! (:conn ctx) (:run-id ctx)
                               :verdict-gap-disagreement
                               {:branch-id (:id branch)
                                :data {:tool "audit"
                                       :disagreement (name d)}})))
          _ (when (and passed (:relaxation? j))
              (when (and (:conn ctx) (:run-id ctx))
                ;; The thesis is NOT re-pinned to this restatement — that is
                ;; UCLA's shrinking-claim hole, and drift is recorded, never
                ;; adopted.
                (journal/note! (:conn ctx) (:run-id ctx)
                               :thesis-drift
                               {:branch-id (:id branch)
                                :data {:thesis-goal (get-in branch [:thesis :goal])
                                       :established (:established j)}})))]
      (merge
       {:branch (assoc branch :last-audit
                      {:passed passed :proposed-answer answer
                       :established (:established j)
                       :relaxation? (:relaxation? j)})
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
    ;; Inflections of entries already here, plus two plain prepositions that
    ;; were simply missing. A refusal once listed `does`, `follow`, `from` and
    ;; `having` beside its real catches and told the branch to verify or
    ;; remove the word "from".
    "does" "do" "did" "follow" "following" "having" "from" "into" "than"
    "when" "while" "because" "since" "about" "over" "under" "between"
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
    "naturals" "first" "sums" "pairwise" "distinct" "positive"

    ;; The vocabulary of SCOPING an answer: saying what was and was not
    ;; settled, and how sure of it you are. The relevance rung requires this —
    ;; a partial result ships only if it states which questions it leaves open
    ;; — and the coverage rung was refusing the answer for containing it, so
    ;; the two could not both be satisfied. B4 of run 0d0c3560 called `done`
    ;; eight times over twenty turns against a PASSING audit and was refused
    ;; for asserting `stated`, `together`, `asked` and `settled` (vf-w2k).
    ;;
    ;; What you failed to establish is, by construction, not in your evidence.
    ;; A gate that reads naming it as asserting it makes honesty impossible.
    "stated" "states" "fact" "facts" "establish" "establishes" "established"
    "evidence" "check" "checks" "checked" "unchecked" "found" "finds"
    "finding" "findings" "showed" "shown" "showing"
    "prove" "proves" "proving" "support" "supports"
    "supported" "settle" "settles" "settled" "unsettled" "unresolved"
    "resolve" "resolves" "resolved" "ask" "asks" "asked" "question"
    "questions" "answers" "answered" "unanswered" "claim" "claims"
    "claimed" "assert" "asserts" "asserted" "conclude" "concludes"
    "remain" "remains" "remaining"
    "outstanding" "together" "against" "general" "generally" "partial"
    "partially" "whenever" "conditional" "conditionally" "arbitrary"
    "computation" "computations" "computed" "search" "searched" "taken"
    "what" "branch" "branches" "verify" "verifies" "verification"
    "establishing" "demonstrate" "demonstrates" "demonstrated"
    ;; Deictics. A word that points at the document rather than at the
    ;; mathematics cannot be an assertion about the mathematics.
    "here" "above" "below" "within" "throughout"
    "reach" "reaches" "reached" "give" "gives" "yield" "yields" "open"})

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
       ;; A hyphenated compound is one token, so `lean-verified` and
       ;; `engine-confirmed` survived a list holding every one of their parts.
       ;; Both are provenance, which is the one thing an artifact can never
       ;; mention: an artifact is about the problem and says nothing about the
       ;; engine that ran it. A compound with a substantive half —
       ;; `optimal-flow` — is not exempt.
       (remove #(and (str/includes? % "-")
                     (let [parts (remove str/blank? (str/split % #"-"))]
                       (and (seq parts)
                            (every? (fn [p] (or (stopwords p) (< (count p) 4)))
                                    parts)))))
       (filter #(or (re-matches #"[0-9]+(\.[0-9]+)?" %) (>= (count %) 4)))
       distinct))

(def ^:private word-suffixes
  "Stripped longest-first, one only. Enough to see that `enumeration` and
  `enumerating` are the same word, which raw substring matching cannot."
  ["ations" "ation" "ising" "izing" "ings" "ing" "ions" "ion" "ies" "ied"
   "es" "ed" "s"])

(defn- stem
  "The token with one morphological suffix removed, or nil.

  Never below five characters, so nothing is shortened into a prefix that
  matches everything. `enumeration` becomes `enumerat`, which the evidence's
  `enumerating` contains; `residues` becomes `residu`, which `residue`
  contains."
  [w]
  (some (fn [suf]
          (when (and (str/ends-with? w suf)
                     (>= (- (count w) (count suf)) 5))
            (subs w 0 (- (count w) (count suf)))))
        word-suffixes))

(defn number-token?
  "Whether an answer token is a figure rather than a word. The two halves of
  the coverage check treat them completely differently: a figure blocks a
  ship, a word advises the audit."
  [token]
  (boolean (re-matches #"[0-9]+(\.[0-9]+)?" token)))

(defn- covered?
  "Whether `token` appears in the evidence.

  Numbers are matched exactly, against the artifacts alone. That is the strict
  half and it stays strict: an answer naming a size, a bound or a witness that
  no engine produced is the fabricated verification report this whole rung
  exists to catch.

  Words get three chances — the token itself, the token with hyphens
  normalised (`optimal-flow` against `optimal flow`), and its stem — because a
  refusal over `residues` when the evidence says `residue` teaches the model
  to strip its prose rather than to verify anything."
  [token artifact-text word-text]
  (if (number-token? token)
    (str/includes? artifact-text token)
    (or (str/includes? word-text token)
        (and (str/includes? token "-")
             (or (str/includes? word-text (str/replace token "-" " "))
                 (str/includes? word-text (str/replace token "-" ""))))
        (when-let [s (stem token)] (str/includes? word-text s))
        ;; One derivational step further, for long words only: `computability`
        ;; against `computable` is the same complaint as `residues` against
        ;; `residue`, and no suffix list reaches it. Deliberately coarse — the
        ;; haystack is a handful of artifacts, and this half of the rung is a
        ;; check on vocabulary rather than on arithmetic.
        (and (>= (count token) 8) (str/includes? word-text (subs token 0 6))))))

(defn uncovered-tokens
  "Answer tokens no confirmed artifact mentions.

  The claim-evidence gate, deterministic and with no model in the path. An
  answer asserting a number that appears nowhere in the evidence is a
  fabricated verification report, which is the failure dirge PR 749 was
  written for."
  ([answer artifacts] (uncovered-tokens answer artifacts nil))
  ([answer artifacts word-context]
   (let [artifact-text (str/lower-case
                        (str/join " " (for [a artifacts]
                                        (str (:claim a) " " (:code a) " "
                                             (pr-str (:witness a))))))
         ;; Words may also come from `word-context`: the problem statement the
         ;; harness handed the branch, and a PASSING audit's restatement of
         ;; what the evidence establishes. Neither is something the model can
         ;; fabricate — one the harness wrote, the other a gate that already
         ;; passed on the merits — and an answer that says which of the
         ;; problem's questions it did not settle has to name them, using
         ;; words that are absent from the evidence for the only reason that
         ;; matters: nobody established them.
         ;;
         ;; Numbers get none of this. A figure has to come from an artifact.
         ;; That is the strict half and the whole reason the rung exists.
         word-text (str artifact-text " "
                        (str/lower-case
                         (if (coll? word-context)
                           (str/join " " (remove nil? word-context))
                           (str word-context))))]
     (remove #(covered? % artifact-text word-text) (answer-tokens answer)))))

;; --- and the answer has to answer THIS problem ------------------------------
;;
;; Everything above checks the answer against the EVIDENCE. Nothing checked it
;; against the QUESTION, and a run shipped a true, verified, independently
;; reviewed statement about four oriented edges of a 4-cycle as its answer to
;; "when can 2D phase unwrapping be done exactly, and by a polynomial-time
;; algorithm?" (vf-eq9).
;;
;; Both gates were right by their own criteria. The audit compares the answer
;; to the THESIS, and the thesis had itself drifted off the problem over a
;; hundred turns. The coverage rung found every token supported, because the
;; answer WAS a confirmed artifact copied out verbatim. `advances-thesis?`
;; guards claims against the thesis for exactly this reason; nothing guarded
;; the thesis, or the answer, against the problem.

(defn engages-problem?
  "Whether the answer shares any substantive vocabulary with the problem.

  The free rung, and deliberately the weakest one: lexical overlap cannot tell
  an answer to the question from an answer about the question's machinery — the
  4-cycle answer shares `flow` and `cost` with its problem and passes here. Zero
  overlap is the only thing it decides, and it decides it with no model in the
  path, which is what makes it the floor under a judge that fails open.

  A problem with no substantive vocabulary of its own — a stub, a test
  fixture — means there is nothing to be irrelevant to, and this passes."
  [problem answer]
  (let [terms (set (answer-tokens problem))]
    (or (empty? terms)
        (boolean (some terms (answer-tokens answer))))))

(defn- relevance-prompt
  "Ask whether the answer responds to the problem, and to nothing else.

  Deliberately not the audit's question. The audit asks whether the evidence
  supports the answer, which is why it can pass an answer that is fully
  supported and about something else. This one is not shown the artifacts at
  all, so there is nothing here to be talked into: only the problem and the
  text about to ship."
  [problem answer]
  (str "A harness is about to ship this answer as its response to the problem"
       " below. Decide ONE thing: does the answer respond to what the problem"
       " asks?\n\n"
       "PROBLEM:\n" problem "\n\n"
       "PROPOSED ANSWER:\n" answer "\n\n"
       "The answer does NOT have to solve the problem. A partial result is a"
       " legitimate answer provided it says that is what it is: an answer that"
       " settles none of what was asked, states plainly which questions it"
       " leaves open, and gives what it established instead, is PASS.\n\n"
       "Answer FAIL when the answer offers something else AS THOUGH it were the"
       " answer — a lemma about the machinery, a corollary, a side result —"
       " without saying what the problem asked for and did not get. An answer"
       " that is true, and verified, and simply about a different question than"
       " the one posed is FAIL.\n\n"
       "Do not judge whether the answer is correct or well supported; other"
       " gates have already done that, and you are not being shown the evidence."
       " Judge only whether it responds to the question.\n\n"
       "On a line reading `ASKS: <text>`, state in one sentence what the problem"
       " asks for.\nOn a line reading `SUPPLIES: <text>`, state in one sentence"
       " what the answer actually supplies."))

(defn- labelled-line
  "The text after `LABEL:` on the last line carrying one, or nil."
  [text label]
  (last (keep (fn [line]
                (when-let [m (re-matches
                              (re-pattern (str "(?i)" label "\\s*:\\s*(.+)"))
                              (str/trim line))]
                  (str/trim (second m))))
              (str/split-lines (str text)))))

(defn relevance-block
  "The done-gate refusal for an answer that does not respond to the problem, or
  nil to let it through.

  Only an explicit FAIL blocks. This rung fails OPEN, against the convention
  everywhere else here, and the difference is what it guards: the other gates
  guard evidence, where a check that could not run must never wave a claim
  through. This one is editorial, it runs only after every evidence rung has
  already passed, and stranding a verified answer because a judge call died is
  the worse of the two failures. `judge` already retries an unparseable verdict
  three times and journals every attempt before it reaches here."
  [j]
  (when (= :fail (:verdict j))
    (let [asks (labelled-line (:text j) "ASKS")
          supplies (labelled-line (:text j) "SUPPLIES")]
      (str "This answer does not respond to the problem.\n\n"
           (when asks (str "The problem asks for: " asks "\n"))
           (when supplies (str "Your answer supplies: " supplies "\n"))
           (when (or asks supplies) "\n")
           "A partial result is a perfectly good answer here, but it has to say"
           " that is what it is. State which of the problem's questions you did"
           " not settle, and then what you established instead — that ships."
           " What does not ship is a side result presented as though it were the"
           " answer.\n\n"
           (or (not-empty (str/trim (str (:text j)))) (:reason j))))))

(defmethod run-tool "done" [{:keys [branch] :as ctx}]
  ;; The answer may be omitted, in which case the last PASSING audit's
  ;; approved text ships verbatim. Two consecutive live runs produced
  ;; audit-approved results and then died re-typing them: the model reformats
  ;; the approved answer, the verbatim rung refuses, and the turn cap lands
  ;; before a re-audit (vf-691). Shipping by reference removes the
  ;; transcription step; every other rung still runs, and an explicitly
  ;; supplied answer keeps the verbatim rule.
  (let [audit (:last-audit branch)
        supplied (arg ctx :answer)
        answer (if (str/blank? (str supplied))
                 (when (:passed audit) (:proposed-answer audit))
                 supplied)
        confirmed (state/confirmed-artifacts branch)
        review (:last-review branch)
        template-confirmed? (some #(and (= :slow (:tier %))
                                        (= :confirmed (:claim-status %)))
                                  (:artifacts branch))
        ;; Measurements cover tokens even though they cannot carry the answer.
        ;; This rung exists to catch FABRICATED specifics, and a number Octave
        ;; computed with the harness watching is not fabricated — refusing it
        ;; would leave a branch unable to state its own measurement (vf-0of).
        ;; The strength of the evidence is the audit's question, not this one's.
        own (concat confirmed (state/empirical-artifacts branch))
        ;; And what the rest of the run established. A fork is shown its
        ;; parent's confirmed claims in its opening message and every branch
        ;; sees the shared-artifact block, so refusing the answer that cites
        ;; them punishes a branch for reading what the harness handed it
        ;; (vf-b9c). B4.2.2.2 was refused for stating `8` and `6` — its own
        ;; parent's exhaustively verified count and cost, already shipped in
        ;; an accepted answer one branch over.
        elsewhere (when (and (:conn ctx) (:run-id ctx))
                    (journal/corroborating-artifacts
                     (:conn ctx) (:run-id ctx) (:id branch)))
        evidence (concat own elsewhere)
        problem (:problem branch)
        word-context [problem (when (:passed audit) (:established audit))]
        uncovered (uncovered-tokens answer evidence word-context)
        uncovered-numbers (filter number-token? uncovered)
        ;; Tokens only a sibling covers. Provenance, not a rung: the answer is
        ;; leaning on evidence this branch did not produce, and the run record
        ;; should say so rather than the write-up having to reconstruct it.
        borrowed (when (seq elsewhere)
                   (seq (remove (set uncovered)
                                (uncovered-tokens answer own word-context))))
        block (cond
                (nil? answer)
                (str "No answer was supplied and no audit has passed. Call"
                     " `audit` with {claim, proposedAnswer}; once it passes,"
                     " `done` with no answer ships the audited text exactly.")

                (empty? confirmed)
                "This branch has no confirmed artifact. Nothing has been verified."

                (not (and audit (:passed audit)))
                (str "The pre-ship audit has not passed. Call `audit` with"
                     " {claim, proposedAnswer} first.")

                (not= (:proposed-answer audit) answer)
                (str "The audit passed for a different answer. It approved:\n  "
                     (:proposed-answer audit)
                     "\nand you are shipping:\n  " answer
                     "\nEither re-run `audit` against the answer you intend to"
                     " ship, or call `done` with no answer to ship the audited"
                     " text exactly.")

                (and (not review) (not template-confirmed?))
                (str "Nothing has been independently cross-checked. Either run"
                     " `review` with an encoding different in shape from the one"
                     " that confirmed the result, or use `verify_template`, whose"
                     " cross-check is built in.")

                (and review (not (:passed review)) (not template-confirmed?))
                "The last review FAILED. Resolve the disagreement before shipping."

                (and audit (:relaxation? audit)
                     (seq (uncovered-tokens
                           answer
                           [{:claim (or (:established audit) "")
                             :code "" :witness nil}])))
                (str "The audit flagged this answer as a relaxation of the thesis."
                     "\n\nThe thesis asked for: "
                     (get-in branch [:thesis :goal])
                     "\nThe evidence establishes: "
                     (or (:established audit) "nothing stated")
                     "\n\nYour answer asserts the full thesis claim, but the audit"
                     " itself says the evidence only establishes the weaker claim"
                     " above. Either state the answer as what is established, or"
                     " confirm evidence that establishes the full thesis and"
                     " re-run `audit`.")

                ;; NUMBERS ONLY. The word half of this check was a lexical
                ;; proxy for "does the answer assert something unsupported",
                ;; and the audit answers that question with semantics, in
                ;; context, and had already passed. Three rounds of widening
                ;; the stopword list took B4 from eight refused words to one
                ;; and it still could not ship; ordinary English does not run
                ;; out (vf-kpn). The uncovered words now go to the audit and
                ;; the journal instead of the door.
                (seq uncovered-numbers)
                (str "Your answer states figures no artifact supports: "
                     (str/join ", " (map #(str "`" % "`") (take 8 uncovered-numbers)))
                     ".\nA number in an answer has to come from something an"
                     " engine confirmed or measured — that is the difference"
                     " between a verification report and a fabricated one."
                     " Either verify these or remove them from the answer.")

                (not (engages-problem? problem answer))
                (str "This answer shares no substantive term with the problem"
                     " statement. Whatever it establishes, it is not an answer to"
                     " the question that was asked.")

                ;; Last, because it is the only rung that costs a model call.
                (not (str/blank? (str problem)))
                (relevance-block
                 (judge ctx :relevance (relevance-prompt problem answer))))]
    ;; Journalled whether or not anything blocked, so the run record still
    ;; shows what the lexical check saw even though it no longer decides.
    (when-let [words (and (:conn ctx) (:run-id ctx)
                          (seq (remove number-token? uncovered)))]
      (journal/note! (:conn ctx) (:run-id ctx) :uncovered-words
                     {:branch-id (:id branch) :turn (:turn ctx)
                      :data {:words (vec (take 20 words)) :blocked? (some? block)}}))
    (when (and borrowed (:conn ctx) (:run-id ctx))
      (journal/note! (:conn ctx) (:run-id ctx) :cross-branch-citation
                     {:branch-id (:id branch) :turn (:turn ctx)
                      :data {:tokens (vec (take 20 borrowed))
                             :sources (vec (distinct (keep :branch_id elsewhere)))}}))
    (if block
      (fail branch (str "`done` refused.\n\n" block) :done-block block)
      {:branch (assoc branch :final-answer answer :status :done)
       :category :success
       :progress? true
       :done? true
       :answer answer
       :result (str "Answer accepted.\n\n" answer)})))

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
      (malformed branch (str "`theses` must be a non-empty array of"
                        " {goal, subClaims, technique} objects."))

      (> (count proposals) max-branch-theses)
      (malformed branch (str "At most " max-branch-theses " theses per call; you proposed "
                             (count proposals) "."))

      (not (every? #(and (map? %) (string? (:goal %))) proposals))
      (malformed branch "Every thesis must be an object with a `goal` string.")

      :else
      ;; The first commits THIS branch; the rest become siblings. The scheduler
      ;; reads :pending-branch-theses after the turn and clears it, so a tool
      ;; never creates a branch itself — one place owns the branch table.
      (let [[mine & others] proposals
            thesis (assoc mine :set-at-turn (:turn ctx))
            ;; vf-3wg: premises for the goal THIS branch commits to. The
            ;; siblings are separate branches the scheduler opens; they are
            ;; not drafted from here.
            prem (premises-for ctx (:goal thesis))]
        (ok (cond-> (assoc branch :thesis thesis
                           :pending-branch-theses (vec others))
              (seq (:names prem))
              (update :premises-served (fnil into #{}) (:names prem)))
            (str "Committed to: " (:goal thesis)
                 (when (seq others)
                   (str "\nRequested " (count others) " sibling branch(es) for: "
                        (str/join "; " (map :goal others))
                        "\nThey explore independently and share this branch's"
                        " failure log, so none of you will repeat another's"
                        " dead end."))
                 (premises-block prem))
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

(defn- register-session!
  "Record a freshly created engine session where run teardown can always find
  it, whatever happens to the branch map afterwards.

  vf-cfp. A session used to live ONLY in the branch, attached by returning
  [session updated-branch], so every path that falls back to a pre-session
  branch value dropped the handle for good. Three do: the tool-level `catch`,
  which sits outside the `let` that shadows `branch` and so returns the branch
  without the session; the beam's turn deadline, which keeps the branch as it
  was BEFORE the turn; and a turn that throws, which does the same.

  Nineteen Lean repls had accumulated this way, 2.1GB, the oldest up a day and
  a half, each with about nine seconds of CPU — a Mathlib import and nothing
  after, which is what a session created on a turn that then blew its deadline
  looks like.

  beam.clj states the rule three lines above where Prolog sessions are
  registered: the stop path must not depend on the agent's state. This puts
  Lean and Octave on the same footing, so a lost branch value costs a turn
  rather than a process."
  [ctx kind s]
  (when-let [reg (:engine-sessions ctx)]
    (swap! reg conj {:kind kind :session s}))
  s)

(defn- lean-session!
  "The branch's Lean session: a warmed one if the pool has it, otherwise a fresh
  import. Returns [session branch].

  Waits briefly on a slot whose import is still running rather than starting a
  second import alongside it — two concurrent imports are slower than one, so
  racing the pool would be worse than either warming or not warming."
  [{:keys [branch config] :as ctx}]
  ;; `alive?`, not merely present. A session that died was handed back on every
  ;; later call, so the branch could never obtain a working one and every Lean
  ;; call failed with "the session is dead" — gen-26 B3 was culled on exactly
  ;; that, three consecutive failures of which two were the outage.
  (if-let [s (when (lean-repl/alive? (:lean branch)) (:lean branch))]
    [s branch]
    (let [s (or (lean-pool/checkout! (get-in config [:warmup :checkout-wait-ms] 60000))
                (doto (lean-repl/create-session (get-in config [:engines :lean]))
                  (lean-repl/mathlib-env)))]
      (register-session! ctx :lean s)
      [s (assoc branch :lean s)])))

(def ^:private lean-hints
  "Recurring Lean failures, and what each one actually means.

  Ranked by how often they cost a turn across gen-22, gen-23 and gen-24: 34
  Lean rejections, of which 8 linarith/omega, 7 syntax, 7 unsolved goals, 4
  instance resolution, 3 rewrite. The engine's own text is printed above these
  and is often precise; what it does not say is which of them is a TACTIC
  fault the branch can fix in one line and which is a real gap in the proof.
  Those look identical from the goal state, and a branch that reads a tactic
  limitation as a mathematical obstruction abandons a true lemma.

  Each entry names the fault and the tactic that addresses it, and stops
  there. Supplying the proof would move the work from the branch to the
  harness, and the harness is not the thing being tested."
  [[#"(?i)linarith failed|omega could not"
    (str "`linarith` and `omega` are LINEAR arithmetic: neither can multiply"
         " two unknowns, and a goal comparing products or powers of variables"
         " is out of reach for both however true it is. If the goal needs"
         " hypotheses multiplied together — bounding E*S from E ≤ 2V and"
         " S ≤ 2V, say — use `nlinarith`, which adds products of hypotheses to"
         " the problem, and give it the products it should consider as hint"
         " terms: `nlinarith [sq_nonneg V, mul_le_mul hE hS ...]`. For a chain"
         " of monotone steps, `mul_le_mul`, `Nat.mul_le_mul` and `pow_le_pow_left`"
         " prove it directly and say more clearly what is going on.")]

   [#"(?i)unexpected token 'in'"
    (str "Mathlib 4 writes big operators with ∈, not `in`: `∑ x ∈ s, f x` and"
         " `∏ x ∈ s, f x`. The `∑ x in s` spelling is deprecated and no longer"
         " parses. This is the single most common syntax failure here, because"
         " the old form is everywhere in the training data.")]

   [#"(?i)failed to synthesize.{0,40}GetElem"
    (str "You indexed with bracket syntax something that is a FUNCTION, not a"
         " collection. For `f : Fin n → α`, application is `f i`, not `f[i]`."
         " `GetElem` is for arrays, lists and vectors.")]

   [#"(?i)failed to synthesize.{0,40}Fintype"
    (str "Lean cannot enumerate that type, and a Finset sum or a `∑ x, …` over"
         " it needs to. Either derive it — `deriving instance Fintype for X`,"
         " which Lean's own hint above suggests — or state the lemma over a"
         " type that already has the instance, `Fin n` being the usual choice.")]

   [#"(?i)failed to synthesize.{0,60}Decidable"
    (str "The predicate is not decidable as stated, so Lean cannot build the"
         " `if` or the `Finset.filter` it needs. Add the instance as a binder"
         " — `[DecidableRel G.Adj]`, `[DecidablePred p]` — or `open Classical"
         " in` before the declaration to get it classically.")]

   [#"(?i)rewrite.{0,20}failed|Did not find an occurrence"
    (str "`rw` matches the pattern SYNTACTICALLY against the goal, so a lemma"
         " that is true here still fails if the goal is not in its exact shape."
         " The goal above is what it had to match. `simp only [lemma]` matches"
         " up to reducible unfolding, and `conv` lets you point at the"
         " subterm you meant.")]

   [#"(?i)unknown (constant|identifier)"
    (str "That name does not exist in this environment. It is a lookup, not a"
         " proof problem: `lean_search` finds the real name, and the one you"
         " want is often spelled differently or namespaced.")]

   [#"(?i)no goals"
    (str "More tactics than goals — something above already closed it. Delete"
         " the trailing tactics, or check that a `·` / `case` block is not"
         " claiming a branch that no longer exists.")]

   [#"(?i)unsolved goals"
    (str "The proof ran to the end with goals still open; the ones printed"
         " above are what remain. If a case looks unreachable, it still needs"
         " discharging — `omega`, `simp` or an explicit `exact absurd …` on"
         " the contradictory hypothesis.")]])

(defn- name-segments
  "The underscore-separated pieces of a Lean name, namespace dropped.

  `List.isChain_of_mem_splitByLoop` yields
  #{\"isChain\" \"of\" \"mem\" \"splitByLoop\"}. The namespace goes because a
  whole invented family shares it and it says nothing; camelCase is NOT split
  further, because `splitByLoop` is the unit a branch invented and reporting
  `split` back at it would name something Mathlib really does have."
  [nm]
  (->> (str/split (str (last (str/split (str nm) #"\."))) #"_")
       (remove str/blank?)
       set))

(defn- invented-stem
  "A word shared by three or more of the unknown names, or nil.

  Three, not two: `foo` and `foo_append` are a plausible pair of real lemmas
  misremembered, while six names built from one stem is a branch that has
  designed an API and then asked Lean for it. Common Lean particles are
  excluded or every family would share `of` and `mem`."
  [text]
  (let [names (map second (re-seq #"[Uu]nknown (?:constant|identifier) `([^`]+)`" (str text)))]
    (when (<= 3 (count names))
      (let [particles #{"of" "mem" "eq" "ne" "le" "lt" "iff" "not" "list" "to" "is"
                        "the" "and" "or" "in" "at" "by" "self" "get" "map" "set"
                        "cons" "nil" "append" "length" "head" "tail"}
            ;; Counted case-insensitively but reported as written, so the
            ;; branch sees the identifier it typed.
            freq (->> (mapcat name-segments names)
                      (remove #(particles (str/lower-case %)))
                      (group-by str/lower-case))]
        (some->> freq
                 (filter #(<= 3 (count (val %))))
                 (sort-by (comp - count val))
                 first
                 val
                 first)))))

(defn lean-hint
  "What the harness can say about a Lean error beyond quoting it, or nil.

  nil when nothing matches, deliberately: an invented hint on an unfamiliar
  error is worse than none, because the branch has no way to tell the two
  apart and will spend turns on advice the harness made up."
  [text]
  (let [t (str text)]
    (if-let [stem (invented-stem t)]
      ;; Checked before the table, because the unknown-constant entry there is
      ;; right for ONE bad name and actively wrong for a family: it sends the
      ;; branch to lean_search for the correct spelling of something that has
      ;; no correct spelling. gen-30 B1.4 asked for six `splitByLoop` lemmas at
      ;; once and was told to go looking for them.
      (str "Several of those names share `" stem "`, which means this is not a"
           " misspelling — nothing in Mathlib is called that under any"
           " spelling, and you have designed an API and then asked for it."
           " Searching for the right name will not find one.\n\nEither build"
           " what you need from lemmas that do exist — `lean_search` one"
           " concept at a time, not the family — or state and prove the"
           " missing piece yourself as its own claim and cite it.")
      (some (fn [[re hint]] (when (re-find re t) hint)) lean-hints))))

(defn- lean-error-text [errors]
  (str/join "\n" (map #(str "  " (str/replace (str (:data %)) "\n" "\n  ")) errors)))

(defmethod run-tool "verify_lean" [{:keys [branch] :as ctx}]
  (if-let [m (or (missing ctx :claim :lean) (vague-claim ctx))]
    (malformed branch m)
    (if-let [served (claim-dedup ctx (arg ctx :claim))]
      served
      ;; Every exit below except a confirmation releases the claim. Lean has no
      ;; refuting outcome — it rejects PROOFS, never claims — so a branch that
      ;; cannot get its snippet past the elaborator has learned nothing about
      ;; whether the statement is true, and must not hold the claim shut
      ;; against a branch that can.
      ;; NOT {:keys [ok ...]}: that shadows the `ok` helper for this whole
      ;; body, and every exit here happened to use `fail` or `unavailable`, so
      ;; the trap sat unsprung until a branch needed to return a plain result.
      ;; The failure is a Boolean-cannot-be-cast-to-IFn at runtime.
      (let [{lint-ok :ok :keys [warnings]} (lint/lint-lean (arg ctx :lean))]
      (if-not lint-ok
        ;; `sorry` compiles with a warning, so without the lint a snippet that
        ;; proves nothing would be recorded as confirmed. Observed in the
        ;; Frankl run in the original harness.
        (do (settle-claim! ctx (arg ctx :claim) :released nil)
            (fail branch (str "Lean lint rejected the snippet — nothing was run:\n  • "
                              (str/join "\n  • " warnings))))
        (try
          (let [[s branch] (lean-session! ctx)
                claim (arg ctx :claim)
                r (lean-repl/run-command s (arg ctx :lean))]
            (cond
              (:error r)
              ;; An outage, not a failed verification. `unavailable` already
              ;; argues this for the thrown-exception path — "gen-18 B3 was
              ;; culled after six consecutive failures... one of the six was
              ;; Lean is unavailable, a fact about the process pool" — and a
              ;; REPL that dies mid-request returns the error instead of
              ;; throwing, which is the commoner path. gen-26 B3 was culled the
              ;; same way, two generations later.
              (do (settle-claim! ctx claim :released nil)
                  (unavailable branch "Lean" (ex-info (str (:error r)) {})))

              (seq (:sorries r))
              (do (settle-claim! ctx claim :released nil)
                  (fail branch
                        (str "The snippet elaborated but left " (count (:sorries r))
                             " `sorry` goal(s) open, so it proves nothing. Close them,"
                             " or use proof_start to develop the proof step by step.")
                        :failure {:claim claim :reason "the proof contained sorry"}))

              ;; Elaborated fine and asserts nothing. `True` is closed by
              ;; `trivial` and substantiates no claim, so there is nothing to
              ;; bank — but the snippet is how a branch runs `#print` or
              ;; `#check`, and that inspection is worth keeping: gen-30 B1.3
              ;; learned Chain' is IsChain this way and used the constructor
              ;; names in the lemma it proved next. So the output comes back
              ;; and the claim is RELEASED rather than settled, leaving it open
              ;; to a branch that can actually prove it.
              (and (:ok r) (lint/vacuous-lean-statement? (arg ctx :lean)))
              (do (settle-claim! ctx claim :released nil)
                  (ok branch
                      (str "Lean accepted it, and it asserts nothing: the"
                           " declaration concludes `True`, which `trivial`"
                           " closes for any input. Nothing was recorded"
                           " against the claim.\n\n"
                           (if-let [msgs (seq (:messages r))]
                             (str "Output:\n"
                                  (str/join "\n" (map #(str "  " (:data %)) msgs)))
                             "The snippet produced no output.")
                           "\n\nUse this for `#print` and `#check` freely. To"
                           " settle the claim, state it as a theorem whose"
                           " conclusion is the claim itself.")))

              (:ok r)
              (let [code (arg ctx :lean)
                    ;; The whole verdict, not just its boolean. Dropping the
                    ;; reason here left the branch a paragraph of generic
                    ;; advice — check your quantifiers — when the reviewer had
                    ;; already said which quantifier (vf-9p2).
                    faithful? (encoding-faithful?
                               ctx claim code
                               {:engine "a Lean 4 declaration"
                                :outcome "Lean accepted it with no goals left open"
                                :direction :confirms
                                :extra lean-faithfulness-note
                                :structural (faithful/check-lean claim code)})]
                (if (:ok? faithful?)
                  (let [artifact {:kind :lean :claim claim :code code
                                  :claim-status :confirmed :tier :fast}]
                    (settle-claim! ctx claim :confirmed artifact)
                    {:branch branch :category :success :progress? true
                     :result "Lean accepted it. Claim CONFIRMED."
                     :artifact artifact})
                  (do
                    ;; Lean proved SOMETHING, just not this. The claim is
                    ;; untouched by that, and the next branch may state the
                    ;; theorem the claim actually describes.
                    (settle-claim! ctx claim :released nil)
                    (fail branch
                          (str "Lean accepted the declaration, so the PROOF is sound —"
                               " but review found the STATEMENT is not the claim, so"
                               " what you proved is not what you said.\n\n"
                               (:reason faithful?)
                               "\n\nCheck the quantifiers, their ranges, the"
                               " hypotheses and the direction of each inequality"
                               " against the claim's wording, then state the theorem"
                               " the claim describes.")
                          :failure {:claim claim :reason (:reason faithful?)}
                          :artifact {:kind :lean :claim claim :code code
                                     :claim-status :unfaithful :tier :fast}))))

              :else
              ;; No artifact. A snippet Lean rejects is a failed proof ATTEMPT,
              ;; which says nothing about whether the claim is true — this used
              ;; to record it with claim-status :refuted, so a type error read
              ;; as evidence against the claim. The failure log keeps the
              ;; record; the artifact table should not, and neither should the
              ;; claim registry.
              (let [etext (lean-error-text (:errors r))]
                (settle-claim! ctx claim :released nil)
                (fail branch (str "Lean rejected it:\n" etext
                                  "\n\nThat is a problem with the proof, not evidence"
                                  " about the claim."
                                  (when-let [h (lean-hint etext)]
                                    (str "\n\n" h)))
                      :failure {:claim claim
                                :reason (str "lean: " (some-> (first (:errors r)) :data
                                                              (str/replace #"\s+" " ")
                                                              (subs 0 (min 160 (count (str (:data (first (:errors r)))))))))}))))
          (catch Throwable e
            (settle-claim! ctx (arg ctx :claim) :released nil)
            (unavailable branch "Lean" e))))))))

(defmethod run-tool "sketch" [{:keys [branch] :as ctx}]
  ;; Draft-Sketch-Prove (Jiang et al., NeurIPS 2022): the draft is worth
  ;; banking only because it is Lean rather than prose. A skeleton with
  ;; `sorry` steps is machine-checkable without being proved — the elaborator
  ;; rejects an invented lemma as an unknown constant, and every `sorry` is a
  ;; real goal with a type — so the same run-command verify_lean uses decides
  ;; here too, with the polarity flipped: elaborates WITH sorries is a plan,
  ;; elaborates without them is a proof that belongs in verify_lean, and
  ;; elaboration failure is a fact about the citations.
  ;;
  ;; Every exit releases the claim. A sketch settles nothing, and holding the
  ;; claim shut against a branch that can actually prove it would trade a
  ;; verification for a plan.
  (if-let [m (missing ctx :claim :lean)]
    (malformed branch m)
    ;; NOT {:keys [ok ...]} — that shadows the `ok` helper for this whole
    ;; body; verify_lean documents the trap (a Boolean-cannot-be-cast-to-IFn
    ;; at runtime, only on the path that needs the helper).
    (let [{lint-ok :ok :keys [warnings]}
          (lint/lint-lean (arg ctx :lean) {:allow-sorry? true})]
      (if-not lint-ok
        (do (settle-claim! ctx (arg ctx :claim) :released nil)
            (fail branch (str "Lean lint rejected the sketch — nothing was run:\n  • "
                              (str/join "\n  • " warnings))))
        (try
          (let [[s branch] (lean-session! ctx)
                claim (arg ctx :claim)
                code (arg ctx :lean)
                r (lean-repl/run-command s code)]
            (cond
              (:error r)
              ;; An outage, not a failed plan — the same reasoning as
              ;; verify_lean's identical branch: an engine outage is a fact
              ;; about the process pool, not about this branch's approach.
              (do (settle-claim! ctx claim :released nil)
                  (unavailable branch "Lean" (ex-info (str (:error r)) {})))

              (not (:ok r))
              ;; The invented-citation path, and the whole reason the sketch
              ;; is Lean rather than prose: `unknown constant` is the
              ;; elaborator refusing a name, and no proof fixes a citation
              ;; that does not exist. Same release verify_lean gives a
              ;; rejection — a failed plan says nothing about the claim.
              (let [etext (lean-error-text (:errors r))]
                (settle-claim! ctx claim :released nil)
                (fail branch (str "The skeleton does not elaborate — its structure or"
                                  " citations were rejected:\n" etext
                                  "\n\nA sketch has to cite real lemmas; this check is"
                                  " what makes it a plan rather than prose."
                                  (when-let [h (lean-hint etext)]
                                    (str "\n\n" h)))))

              (empty? (:sorries r))
              (do (settle-claim! ctx claim :released nil)
                  (fail branch
                        (str "This elaborated with no `sorry` left open, so it is a"
                             " finished PROOF, not a sketch. Send exactly this code to"
                             " `verify_lean` with the same claim, and it can be recorded"
                             " as confirmed.")))

              :else
              (let [sorries (:sorries r)]
                (settle-claim! ctx claim :released nil)
                ;; `ok`, not :success — a plan is not progress toward a
                ;; verified result, and the cull and stall guards must not
                ;; read it as one. :claim-status :sketch is inert: the ledger
                ;; renders it in its own unverified section, seed-from-run!
                ;; selects only 'confirmed', and the done gate's coverage
                ;; reads confirmed/empirical.
                (ok branch
                    (str "The skeleton elaborates with " (count sorries)
                         " step(s) still open, so the decomposition is sound and"
                         " every citation exists. Recorded as a PLAN, not a result.\n\n"
                         "Open goals:\n"
                         (str/join "\n" (map-indexed (fn [i sg]
                                                       (str (inc i) ". " (:goal sg)))
                                                     sorries))
                         "\n\nClose each goal to turn the plan into evidence —"
                         " `proof_start` on the statement, or `verify_lean` once the"
                         " whole proof is ready.")
                    :artifact {:kind :lean :claim claim :code code
                               :claim-status :sketch :tier :fast}))))
          (catch Throwable e
            (settle-claim! ctx (arg ctx :claim) :released nil)
            (unavailable branch "Lean" e)))))))

(def ^:private max-searches-without-attempt
  "Consecutive `lean_search` calls allowed before the branch has to try
  something.

  lean_search is :neutral — it neither counts against a branch nor advances
  one — so searching is free and a branch can do it forever. gen-27 did: 62 of
  its first 92 turns were searches against 3 verify_lean and 5 proof_start,
  with consecutive runs of 11, 13 and 8 and no attempt in between.

  The harness noticed seventeen times and was ignored seventeen times —
  progress-stalled 0 met to 6 unmet, prologue-cap 0 to 11. arbiter/prefill-for
  already drew the conclusion: gates that WITHHOLD change behaviour, gates that
  SUGGEST do not. The no-good-match message suggests, and a branch reads
  \"Mathlib may not have this, prove it directly\" and searches again with
  different words.

  Charged per search, except that a search returning no hit above the
  relevance floor counts two — see run-tool \"lean_search\". So this is eight
  productive searches or four fruitless ones, and the branch that is finding
  things keeps its rope.

  Eight, which is generous against the observed streaks and leaves real
  exploration alone: a branch that alternates searching and proving never
  reaches it."
  8)

(defmethod run-tool "lean_search" [{:keys [branch config] :as ctx}]
  (if-let [m (missing ctx :query)]
    (malformed branch m)
    (if (>= (:searches-since-attempt branch 0) max-searches-without-attempt)
      ;; Withheld, not advised. The count clears on any verification attempt,
      ;; including one that fails — trying is the point, not succeeding.
      ;; Deliberately not "that is N searches": N is a weighted cost, since a
      ;; search finding nothing counts double, and quoting it as a count would
      ;; overstate by up to 2x. The branch does not need the number.
      (malformed branch
                 (str "You have searched repeatedly without attempting a"
                      " proof — and the ones that found nothing count for"
                      " more — so this search is refused.\n\nIf Mathlib had the lemma under a"
                      " name close to what you have been asking for, it would"
                      " have come up by now. State the lemma yourself and"
                      " prove it — `verify_lean` for a whole declaration, or"
                      " `proof_start` to develop it step by step. A failed"
                      " attempt clears this and is worth more than another"
                      " search: it tells you which step is actually hard."))
      (try
        (let [q (arg ctx :query)
              hits (lean-search/search (get-in config [:engines :lean]) q
                                       (or (arg ctx :top_k) 10))
              ;; A search that finds nothing costs double. Charging both the
              ;; same gave a branch mining Mathlib successfully and a branch
              ;; rewording a doomed query the same eight turns, and they are
              ;; not the same situation: no hit above the relevance floor is
              ;; the evidence that the lemma is not there to be found, which
              ;; is precisely when searching again is the wrong move. gen-29
              ;; spent 22 of its first 48 turns here, 13 of them empty.
              cost (if (some lean-search/relevant? hits) 1 2)]
          (ok (update branch :searches-since-attempt (fnil + 0) cost)
              (lean-search/render hits q)))
        (catch Throwable e
          (unavailable branch "Mathlib search" e))))))

(defmethod run-tool "proof_start" [{:keys [branch] :as ctx}]
  (if-let [m (or (missing ctx :claim :theorem) (vague-claim ctx))]
    (malformed branch m)
    ;; This tool appends " := by sorry" to open the goal, so a theorem that
    ;; already carries a body becomes `… := by tac := by sorry` and Lean
    ;; answers "unexpected token ':='" — an error about a parse, not about the
    ;; mistake. One run made this exact error three times in 29 turns with the
    ;; prompt saying statement-only in as many words. Caught here, before a
    ;; session is opened, with a message that names the fix.
    (if-let [cut (let [t (str (arg ctx :theorem))
                       i (str/index-of t ":=")]
                   (when i (str/trimr (subs t 0 i))))]
      (fail branch
            (str "`theorem` takes the STATEMENT ONLY, without the proof — this"
                 " tool supplies the body itself so it can open the goal for"
                 " you. Drop everything from `:=` onwards and call it again as:"
                 "\n\n  " cut
                 "\n\nThen apply tactics one at a time with proof_step."))
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
          (unavailable branch "Lean" e))))))

(def ^:private placeholder-tactic-re
  "`sorry` and `admit` DISCHARGE a goal — Lean only warns — so a step using one
  legitimately reports no goals left, and proof_step is the path that reads
  that as :closed? and banks a confirmed artifact.

  gen-30 a#829 was recorded confirmed on the slow tier with the body
  `cases l with | nil => simp at hlen | cons x rest => sorry`. verify_lean has
  guarded this since the Frankl run, by lint and again by the REPL's
  `sorries`; this path had neither."
  #"\b(sorry|admit)\b")

(defn- sorry-refusal
  "What a branch is told when a step would close on a placeholder."
  [what]
  (str "That would close the proof with " what ", which discharges the goal"
       " without proving anything — Lean emits only a warning, so nothing"
       " here would have caught it downstream. The step is refused and the"
       " goal is unchanged.\n\nProve the case, or if it is genuinely a"
       " separate result, close this proof without it and state that case as"
       " its own claim."))

(defmethod run-tool "proof_step" [{:keys [branch] :as ctx}]
  (cond
    (missing ctx :tactic) (malformed branch (missing ctx :tactic))
    (nil? (:proof branch)) (fail branch "No proof is open. Call proof_start first.")
    ;; Before a session is spent. The tactic text is the common case and the
    ;; cheap one to catch.
    (re-find placeholder-tactic-re (str (arg ctx :tactic)))
    (fail branch (sorry-refusal "`sorry` or `admit`")
          :failure {:claim (:claim (:proof branch))
                    :reason "the proof step used sorry or admit"})
    :else
    (let [s (:lean branch)
          p (:proof branch)
          r (lean-repl/apply-tactic s (arg ctx :tactic) (:state p))]
      (cond
        (:error r)
        ;; Same reasoning as verify_lean: a dead REPL is the pool's fault.
        (unavailable branch "Lean" (ex-info (str (:error r)) {}))

        (not (:ok r))
        ;; The state is NOT advanced on a failed tactic, so the branch can try
        ;; another without unwinding.
        (fail branch (str "The tactic failed; the goal is unchanged:\n"
                          (lean-error-text (:errors r))))

        (:closed? r)
        (let [p (update p :tactics conj (arg ctx :tactic))
              code (str (:theorem p) " := by\n  " (str/join "\n  " (:tactics p)))]
          ;; Two ways a placeholder reaches here despite the text check above:
          ;; the REPL reports a sorry the tactic introduced some other way
          ;; (`exact sorry`, a macro), or an EARLIER tactic carried one and
          ;; this honest step merely finished the proof. The artifact is
          ;; assembled from every tactic, so the second is not hypothetical —
          ;; the banked code would contain the sorry whatever closed it.
          (if (or (seq (:sorries r)) (re-find placeholder-tactic-re code))
            (fail branch (sorry-refusal "a `sorry` left open earlier in this proof")
                  :failure {:claim (:claim p)
                            :reason "the assembled proof contained sorry"})
            {:branch (assoc branch :proof (assoc p :state (:proof-state r) :closed? true))
             :category :success :progress? true
             :result (str "No goals remain — the proof is CLOSED.\n\n" code)
             :artifact {:kind :lean :claim (:claim p) :code code
                        :claim-status :confirmed :tier :slow}}))

        :else
        (ok (assoc branch :proof (-> p
                                     (assoc :state (:proof-state r))
                                     (update :tactics conj (arg ctx :tactic))))
            (str (count (:goals r)) " goal(s) remain:\n\n"
                 (str/join "\n\n" (:goals r)))
            :progress? true)))))

(defmethod run-tool "fetch_artifact" [{:keys [branch conn run-id] :as ctx}]
  ;; The ledger lists claims with ids and leaves the encodings out, so the code
  ;; costs a turn only when a branch actually wants it rather than riding in
  ;; every context block. This is what makes an id actionable.
  ;;
  ;; Deliberately :neutral, via `ok`: a lookup establishes nothing. Reporting
  ;; :success would clear the branch's consecutive-failure count and read as
  ;; progress, which is the "well-formed but useless call" failure the
  ;; progress guards exist to catch.
  (if-let [m (missing ctx :id)]
    (malformed branch m)
    (let [raw (str/trim (str (arg ctx :id)))
          ;; `a#` is this run's own artifacts, `s#` the shared pool a seed was
          ;; copied into — two tables, two id spaces. A bare number means the
          ;; branch's own, which is the common case. `p#` is also this run's
          ;; own artifacts — the ledger's handle for a SKETCH, same table,
          ;; different status, so the prefix survives the round trip.
          shared? (str/starts-with? raw "s#")
          own? (or (str/starts-with? raw "a#") (str/starts-with? raw "p#"))
          sketch? (str/starts-with? raw "p#")
          id (parse-long (str/replace raw #"^[aps]#" ""))
          ;; An explicit prefix is honoured exactly. A BARE number tries this
          ;; run's own artifacts and then falls back to the shared pool:
          ;; observed live, the ledger renders `s#649`, the model passes
          ;; `649`, and insisting on the prefix cost six of the first eleven
          ;; fetches in a run. Dropping punctuation is the obvious thing for a
          ;; model to do and is not worth a turn.
          own (when (and conn run-id id (not shared?))
                (journal/artifact-by-id conn run-id id))
          a (or own
                (when (and conn run-id id (not own?))
                  (journal/shared-artifact-by-id conn run-id id)))
          ;; Which space it actually came from, so the echoed handle matches
          ;; what the ledger showed. Echoing `a#649` for a seeded row taught
          ;; the model a handle that then fails, because an explicit `a#`
          ;; forces the artifacts table.
          from-shared? (and a (nil? own))]
      (if-not a
        (fail branch (str "No artifact " raw " in this run."
                          " Ids come from the settled-state block: `a#12` for"
                          " something this run established, `s#7` for something"
                          " it inherited. A run cannot reach another run's"
                          " artifacts."))
        (ok branch
            (str (if from-shared? "s#" (if sketch? "p#" "a#")) (:id a)
                 " [" (:branch_id a) " " (:kind a) "/" (:tier a) "]"
                 ;; The status travels with the encoding or a refutation reads
                 ;; as an established result — the failure mode that makes
                 ;; sharing refutations worse than withholding them. Seeded
                 ;; rows carry no status column of their own; seed-from-run!
                 ;; copies only confirmed artifacts, so saying so is accurate
                 ;; rather than a guess, and blank was reading as unknown.
                 " status " (if from-shared?
                              "CONFIRMED (inherited from the seed run)"
                              (str/upper-case (str (:claim_status a))))
                 (when (:verdict a) (str ", verdict " (:verdict a)))
                 "\n\nCLAIM\n" (:claim a)
                 "\n\nENCODING\n" (:code a)))))))

(defmethod run-tool "fetch_turn" [{:keys [branch conn run-id] :as ctx}]
  ;; The other half of compaction. Unloading a branch's early turns to one
  ;; line each is only honest if a line can be opened again; before this,
  ;; the digest pointed at a journal the branch had no tool to read.
  ;;
  ;; :neutral for the same reason as fetch_artifact — a lookup establishes
  ;; nothing, and reporting success would clear the failure count.
  (if-let [m (missing ctx :turn)]
    (malformed branch m)
    (let [raw (str/trim (str (arg ctx :turn)))
          n (parse-long (str/replace raw #"^t" ""))
          t (when (and conn run-id n)
              (journal/branch-turn conn run-id (:id branch) n))]
      (if-not t
        (fail branch (str "No turn " raw " on this branch. The digest lists"
                          " your own turns as t1, t2, …; a sibling's turns are"
                          " not readable here — what crossed from them is in"
                          " the settled-state block."))
        (ok branch
            (str "t" (:turn t) " " (:tool_name t)
                 " → " (or (:category t) "neutral")
                 (when (seq (str (:args t))) (str "\n\nARGUMENTS\n" (:args t)))
                 ;; Reasoning is stripped: it is 96% of stored assistant text
                 ;; and is dropped from every prior turn on the way to the
                 ;; wire anyway. Reloading it here would undo that in one call.
                 (when-let [said (some-> (:assistant_text t)
                                         message/strip-think-blocks
                                         not-empty)]
                   (str "\n\nWHAT YOU SAID\n" said))
                 "\n\nRESULT\n" (:result t)))))))

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
  [{:keys [branch config] :as ctx}]
  ;; Same as lean-session!: a dead workspace must not be handed back forever.
  (if-let [s (when (octave/alive? (:octave branch)) (:octave branch))]
    [s branch]
    (let [s (octave/create-session (get-in config [:engines :octave]))]
      (register-session! ctx :octave s)
      [s (assoc branch :octave s)])))

(defmethod run-tool "octave_eval" [{:keys [branch] :as ctx}]
  (if-let [m (missing ctx :code)]
    (malformed branch m)
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
        (unavailable branch "Octave" e)))))

(defn- octave-claim-text
  "How the claim is recorded. An approximate result says so IN the claim, so
  that anything reading artifacts later — the audit, the answer rendering, a
  human — sees the tolerance rather than having to know to look for it."
  [claim tol exact?]
  (if exact? claim (str claim " (numerically, within " tol ")")))

(defn- octave-artifact-code
  "The checked expression together with the workspace that gives it meaning.

  Same problem the Prolog artifact had, and the same fix. `1.014488 > 1` was
  recorded as a confirmed artifact in gen-13: the glpk solve that produced
  1.014488 ran on an earlier turn and was nowhere in the row, so what the
  artifact showed was a comparison of two numbers with no provenance. The
  workspace log is what makes an Octave result auditable at all."
  [session expr]
  (let [steps (seq (remove str/blank? (map :code (octave/snapshot session))))]
    (if steps
      (str (str/join "\n" steps) "\n\n% check:\n" expr)
      expr)))

(defmethod run-tool "verify_octave" [{:keys [branch] :as ctx}]
  (if-let [m (or (missing ctx :claim :expr) (vague-claim ctx))]
    (malformed branch m)
    (if-let [served (claim-dedup ctx (arg ctx :claim))]
      served
      (try
      (let [[s branch] (octave-session! ctx)
            claim (arg ctx :claim)
            tol (or (arg ctx :tol) 0)
            expr (arg ctx :expr)
            r (octave/check s expr tol)]
        (if-not (:ok r)
          (do (settle-claim! ctx claim :released nil)
              (fail branch (str "The expression did not evaluate to a verdict: " (:error r)
                                "\nThat is an encoding problem, not evidence about the claim.")
                    :failure {:claim claim :reason (:error r)}))
          (let [true? (boolean (:verdict r))
                exact? (:exact r)
                code (octave-artifact-code s expr)
                faithful? (encoding-faithful?
                           ctx claim code
                           {:engine (str "an Octave workspace and an expression to"
                                         " evaluate against it")
                            :outcome (str "The expression evaluated to "
                                          (if true? "true" "false")
                                          (if exact?
                                            " as an exact comparison"
                                            (str " within a tolerance of " tol)))
                            :direction (if true? :confirms :refutes)
                            :extra octave-faithfulness-note
                            :structural (faithful/check-octave claim expr)})
                status (cond (not (:ok? faithful?)) :unfaithful
                             true? :confirmed
                             :else :refuted)
                objection (when (= :unfaithful status) (:reason faithful?))
                artifact {:kind :octave
                          :claim (octave-claim-text claim tol exact?)
                          :code code
                          :claim-status status :tier :fast}
                ;; Settled against the claim as the branch stated it, not
                ;; against the tolerance-decorated artifact text — the next
                ;; branch to ask will ask in the plain words.
                _ (settle-claim! ctx claim (verdict-disposition status)
                                 (if (= :confirmed status) artifact objection))]
            (case status
              :confirmed
              {:branch branch :category :success :progress? true
               :result (str "Octave evaluated it to true. Claim CONFIRMED"
                            (if exact?
                              (str " by an exact comparison.\n\nThe comparison was"
                                   " exact; the arithmetic under it is still floating"
                                   " point, so this holds for these inputs at this"
                                   " precision.")
                              (str " numerically, within " tol ".\n\nThis is evidence"
                                   " about a computation, not a proof about the reals:"
                                   " it holds for these inputs at this precision."))
                            " A claim about ALL reals needs Z3 or Lean.")
               :artifact artifact}

              :unfaithful
              (fail branch
                    (str "Octave evaluated it to " (if true? "true" "false")
                         ", but the expression does not answer the"
                         " claim, so the verdict settles nothing either way.\n\n"
                         objection "\n\n"
                         " Check that the expression reads the values the"
                         " workspace computed, that any tolerance matches what"
                         " the claim states, and that the claim is about these"
                         " inputs rather than an infinite family a computation"
                         " cannot reach.\n\nAnd check that you wanted a verdict"
                         " at all. If what you have is a measured value — a"
                         " count, a rate, the point where something crosses —"
                         " `measure` records it as itself, with no comparison"
                         " to get wrong. Forcing a measurement into a boolean"
                         " is how an expression ends up true by construction.")
                    ;; The objection, not a generic sentence. A sibling reading
                    ;; the failure log has to learn what went wrong, or the log
                    ;; is a list of claims nobody may retry for no stated
                    ;; reason (vf-9p2).
                    :failure {:claim claim :reason objection}
                    :artifact artifact)

              :refuted
              (fail branch "Octave evaluated it to FALSE, so the claim is not supported."
                    :failure {:claim claim :reason "the Octave check evaluated to false"}
                    :artifact artifact)))))
      (catch Throwable e
        (settle-claim! ctx (arg ctx :claim) :released nil)
        (unavailable branch "Octave" e))))))

(defn- measurement-claim-text
  "The claim with the number Octave actually returned written into it.

  Same discipline as the tolerance on an Octave check, and for a sharper
  reason: a measurement's whole content is its value, so a claim recorded
  without one is a sentence about a computation nobody can see. Writing it in
  also means the coverage gate can tell a number the run produced from a number
  the model remembered."
  [claim text]
  (str claim " (measured: " text ")"))

(defmethod run-tool "measure" [{:keys [branch] :as ctx}]
  ;; The bankable-measurement path (vf-0of). Everything else that records an
  ;; artifact needs a decidable claim, and the beam only scores artifacts, so
  ;; a branch doing the empirical work a problem asked for scored zero for it
  ;; and got culled. What lands here is an :empirical artifact: real evidence,
  ;; visible to the critic and the cull rule, and explicitly not a proof — the
  ;; done gate still refuses to ship on measurements alone.
  ;; NOT vague-claim guarded, unlike every other artifact producer: a
  ;; measurement NAMES a quantity where a verification ASSERTS a
  ;; proposition, and "the number of optimal flows" is exactly right for
  ;; something whose value is not known until Octave returns it.
  (if-let [m (missing ctx :claim :expr)]
    (malformed branch m)
    (try
      (let [[s branch] (octave-session! ctx)
            claim (arg ctx :claim)
            expr (arg ctx :expr)
            r (octave/measure s expr)]
        (if-not (:ok r)
          (fail branch (str "The expression did not produce a measurement: "
                            (:error r))
                :failure {:claim claim :reason (:error r)})
          (let [code (octave-artifact-code s expr)
                faithful? (encoding-faithful?
                           ctx claim code
                           {:engine (str "an Octave workspace and an expression"
                                         " to measure against it")
                            :outcome (str "The expression evaluated to " (:text r))
                            :direction :measures
                            :extra (str octave-faithfulness-note "\n\n"
                                        measurement-faithfulness-note)
                            :structural (faithful/check-octave claim expr)})
                measured? (:ok? faithful?)
                artifact {:kind :octave
                          :claim (if measured?
                                   (measurement-claim-text claim (:text r))
                                   claim)
                          :code code
                          :witness {:value (:value r)}
                          :claim-status (if measured? :empirical :unfaithful)
                          :tier :fast}]
            (if measured?
              {:branch branch :category :success :progress? true
               :artifact artifact
               :result (str "Measured: " (:text r) " — banked as an empirical"
                            " artifact, with the workspace that produced it.\n\n"
                            "This is a fact about the computation you ran at the"
                            " parameters you ran it at, not a decision, and it"
                            " cannot ship as one: `done` still needs something an"
                            " engine confirmed. What it is for is telling you"
                            " which theorem is worth proving, and standing behind"
                            " a number your final answer states.")}
              (fail branch
                    (str "Octave computed " (:text r) ", but the claim does not"
                         " describe what that expression measures, so the number"
                         " substantiates nothing.\n\n" (:reason faithful?)
                         "\n\nState the claim about the run you actually did — the"
                         " parameters, the range swept, the number of trials —"
                         " rather than about the phenomenon behind it.")
                    :failure {:claim claim :reason (:reason faithful?)}
                    :artifact artifact)))))
      (catch Throwable e
        (unavailable branch "Octave" e)))))
