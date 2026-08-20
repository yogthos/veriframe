;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.state
  "Branch and run state held in memory during a turn.

  SQLite is the durable record; this is the working copy the loop reads
  between appends. Anything a gate needs to decide has to be here, and
  everything here is also journalled, so a resumed run rebuilds it by replay
  rather than by trusting a snapshot.

  A branch is a map, not an object. The engine session and the message history
  are the only parts that cannot be reconstructed from the journal, and the
  session carries its own replay log (see engine/prolog.clj)."
  (:require [clojure.set]
            [clojure.string :as str]))

(defn new-branch
  [{:keys [id parent-id problem prolog messages created-at-turn]}]
  {:id id
   :parent-id parent-id
   :problem problem
   :status :active
   :inactive-reason nil
   :created-at-turn (or created-at-turn 0)
   :prolog prolog
   :messages (or messages [])
   :turns []
   ;; Artifacts this branch produced, newest last. Mirrors the artifacts table.
   :artifacts []
   ;; Consecutive failed verifications; reset by any success.
   :consecutive-failures 0
   ;; Consecutive turns that produced no usable tool call, cleared by any
   ;; well-formed one. Kept apart from :consecutive-failures because a
   ;; malformed fence says nothing about whether the branch's line of inquiry
   ;; is any good, and the cull gate reads that counter as if it did.
   :consecutive-mechanics-failures 0
   ;; The subset of the mechanics tally that were calls the harness declined
   ;; on phase policy (vf-b25/vf-eaw) — perfectly well-formed calls, so the
   ;; cull record must be able to tell them from malformed fences, or the
   ;; reason string lies in the permanent record (gen-30 B3.2 died with
   ;; exactly that false reason).
   :consecutive-policy-refusals 0
   ;; Turns since the last progress event. See gates/progress-stalled?.
   :turns-since-progress 0
   ;; Whether this branch has ever produced anything at all. The stall counter
   ;; arms only after the first progress event, so a branch that produced
   ;; nothing needs a separate bound — dirge PR 739's exploration prologue.
   :any-progress? false
   :thesis nil
   :last-review nil
   :last-audit nil
   ;; Tool-call mechanics only, for the capability tier. Never verification or
   ;; progress signals: a signal may tune a guard that fires on the same thing
   ;; the signal measures (dirge PR 740).
   :mechanics {:calls 0 :parse-errors 0 :auto-repairs 0
               :unknown-tools 0 :truncations 0 :multi-fences 0}
   ;; The draft/prove split (vf-b25): :explore until a sketch is on record,
   ;; :build after. Verification is withheld during explore — the harness's
   ;; one reliably-working gate is the one that WITHHOLDS — and sketch is
   ;; withheld after, or a branch that cannot prove anything retreats into
   ;; re-planning forever.
   :phase :explore
   ;; The turn the CURRENT phase began, not when the branch did. Starts at
   ;; branch creation so a forked branch gets a full explore budget instead
   ;; of inheriting its parent's spent one; reenter-explore moves it.
   :phase-entered-turn (or created-at-turn 0)
   ;; The claim of the last verification that FAILED, and the tool it failed
   ;; on. What the stuck gate names when it withholds an approach (vf-9wx),
   ;; and how it decides whether a Lean sketch is a move this branch can
   ;; actually make.
   :last-failed-claim nil
   :last-failed-tool nil
   ;; The forced reframe: the approach the harness has told this branch to
   ;; abandon, and the turn it said so. While these are set and inside
   ;; :reframe-grace, re-verifying that approach is refused and the branch is
   ;; not culled for the failures that caused the reframe.
   :reframe-claim nil
   :reframe-entered-turn nil
   ;; Gate firings awaiting settlement, as {:id :gate :prediction :window :turn}
   :open-predictions []
   ;; Verification tiers seen. :fast is a one-shot check, :slow is a
   ;; cross-checked template or a review-plus-audit pass.
   :tiers-seen #{}
   :final-answer nil})

(defn active? [branch] (= :active (:status branch)))

(defn confirmed-artifacts [branch]
  (filter #(= :confirmed (:claim-status %)) (:artifacts branch)))

(defn has-confirmed? [branch]
  (boolean (seq (confirmed-artifacts branch))))

(defn empirical-artifacts
  "Measurements this branch banked: a value an engine computed, recorded for
  what it is. Never confirmations — nothing was decided — but not nothing
  either, which is what they counted for before (vf-0of)."
  [branch]
  (filter #(= :empirical (:claim-status %)) (:artifacts branch)))

(defn confirmed-in-last
  "Whether a confirmed artifact landed within the last `n` turns. Incremental
  strategies naturally look like verify size N, fail at N+1, verify N+1, and
  culling them throws away the most valuable branch."
  [branch n]
  (let [cutoff (- (count (:turns branch)) n)]
    (boolean (some #(and (= :confirmed (:claim-status %))
                         (>= (:turn %) cutoff))
                   (:artifacts branch)))))

(defn banked-in-last
  "Whether the branch banked anything in the last `n` turns — a confirmation or
  a measurement.

  What the cull trigger reads, where `confirmed-in-last` used to. A branch
  halfway through a parameter sweep has confirmed nothing by construction, and
  culling it for that is the same mistake as culling the incremental prover:
  the work is going somewhere and the beam cannot see it. The gates that ask a
  branch to SHIP still read `confirmed-in-last`, because a measurement is not
  something to ship."
  [branch n]
  (let [cutoff (- (count (:turns branch)) n)]
    (boolean (some #(and (#{:confirmed :empirical} (:claim-status %))
                         (>= (:turn %) cutoff))
                   (:artifacts branch)))))

(defn finished-key
  "The ranking tuple for a done-eligible branch, best-first component order.

  UCLA's FirstProof selector ranked prose candidates with an LLM judge —
  rigor, then self-consistency, then citation reliability, prefer-the-
  stronger-claim on ties — because nothing about their candidates was
  mechanical. Ours are engine-audited, so the ranking is data and no model
  sits in the path. Components, most important first:

  [non-relaxation slow-seen engine-diversity confirmed-count id]

  - non-relaxation: 1 unless the last audit declared the evidence a
    relaxation of the thesis. A branch that proved the asked claim beats one
    that proved a weakening — UCLA's prefer-the-stronger-claim tie-break,
    mechanical here because the audit already judged it. A nil last-audit
    counts as non-relaxation; only an explicit RELAXATION: yes lowers it.
  - slow-seen: 1 when :slow is in :tiers-seen. A cross-checked template or
    an independent review is stronger evidence than a one-shot check.
    :tiers-seen is the authoritative signal because every slow path records
    it — verify_template stamps the set AND the artifact, review re-
    confirms without producing a new artifact and stamps only the set.
  - engine-diversity: distinct engine kinds among confirmed artifacts.
    Independent engines compose (consensus/engine-agreement's counting
    rule): one Prolog + one Z3 confirmation is stronger than two Z3s.
  - confirmed-count: more engine-confirmed artifacts beats fewer.
  - id: ascending, a stable arbitrary tie-break so the ranking never
    depends on vector order."
  [branch]
  [(if (:relaxation? (:last-audit branch)) 0 1)
   (if (contains? (:tiers-seen branch) :slow) 1 0)
   (count (distinct (keep :kind (confirmed-artifacts branch))))
   (count (confirmed-artifacts branch))
   (:id branch)])

(defn rank-finished
  "Rank done-eligible branches best first, by `finished-key`.

  Expects branches holding :final-answer (the caller has filtered); the
  ranking reads only the evidence they carry, never the order they arrived
  in. Components compare in key order, so a relaxation never outranks a
  direct proof no matter how many artifacts it carries.

  Implemented as sort-by over a normalized key — numeric components negated
  so bigger-is-better becomes ascending, the id left as-is — rather than a
  hand-rolled comparator. The obvious `(or (compare ...) ...)` chain is a
  bug: `compare` returns 0 on ties and 0 is truthy, so the chain never
  falls through to the next component."
  [branches]
  (sort-by (fn [b]
             (let [[non-relax slow diversity confirmed id] (finished-key b)]
               [(- non-relax) (- slow) (- diversity) (- confirmed) id]))
           branches))

(defn record-mechanics
  "Fold one turn's fence signals into the branch's mechanics counters."
  [branch signals]
  (-> branch
      (update-in [:mechanics :calls] inc)
      (cond->
       (:parse-error signals) (update-in [:mechanics :parse-errors] inc)
       (:auto-repaired signals) (update-in [:mechanics :auto-repairs] inc)
       (:truncated signals) (update-in [:mechanics :truncations] inc)
       (:multiple-fences signals) (update-in [:mechanics :multi-fences] inc))))

(def ^:private claim-stopwords
  #{"the" "a" "an" "is" "are" "and" "or" "of" "for" "with" "that" "this" "it"
    "to" "in" "on" "all" "any" "can" "has" "have" "was" "were" "be" "by" "as"
    "clpfd" "prolog" "smt" "lean" "works" "available" "loaded" "basic"
    "supports" "simple" "test" "check" "verify" "verified" "example"})

(defn- singularize
  "Strip one trailing `s`. `flow` and `flows` are the same noun, and a
  set-intersection that cannot see that misses the commonest way two people
  write the same mathematical term — it cost the relevance guard a true match
  the first time it was asked a real question.

  Applied AFTER the stopword filter, so a stopword's stem is never resurrected
  (`supports` is on the list; `support` is not). Never to a word ending in
  `ss`, and never below five characters, so nothing is stemmed into a
  collision."
  [w]
  (if (and (>= (count w) 5)
           (str/ends-with? w "s")
           (not (str/ends-with? w "ss")))
    (subs w 0 (dec (count w)))
    w))

(defn- claim-tokens [s]
  (->> (str/split (str/lower-case (or s "")) #"[^a-z0-9]+")
       (remove str/blank?)
       (remove claim-stopwords)
       (filter #(>= (count %) 4))
       (map singularize)
       set))

(defn advances-thesis?
  "Whether a confirmed claim actually moves the registered plan forward.

  Found by a zebra run that exhausted its turns: at turn 11 the model verified
  `clpfd is available and supports a basic finite-domain constraint`, an engine
  said yes, the harness recorded a confirmed artifact, and the milestone gate
  congratulated it. Nothing about the puzzle had been established. A guard that
  treats every confirmation as progress cannot see a branch verifying its own
  tooling, and that is precisely the successful-but-useless turn the progress
  monitor exists for.

  With no thesis registered the PROBLEM stands in for one. That case used to
  return true unconditionally, which was defensible while the stall counter was
  the only consumer and stopped being so once this became the harness's only
  relevance signal: a branch that never called `thesis` had no guard at all.
  Run 0d0c3560's B3 confirmed `Diagnostic: between(-1,1,X) succeeds for
  X = -1,0,1.` at turn 4, was congratulated for it by the milestone gate, and
  exported it to all three siblings through the shared pool (vf-8fl).

  Only when there is nothing to measure against at all — no thesis, and a
  problem with no substantive vocabulary of its own — does everything count.
  Refusing to credit exploration would be worse than crediting too much, and
  the bar is very low: any one shared substantive word clears it."
  [branch claim]
  (let [sub-claims (get-in branch [:thesis :subClaims])
        goal (get-in branch [:thesis :goal])
        targets (remove str/blank? (conj (vec sub-claims) goal))
        targets (if (seq targets) targets (remove str/blank? [(:problem branch)]))
        ct (claim-tokens claim)]
    (if (every? empty? (map claim-tokens targets))
      true
      (boolean (some #(seq (clojure.set/intersection ct (claim-tokens %)))
                     targets)))))

(defn has-relevant-confirmed?
  "Whether the branch has confirmed anything that engages what it is working
  on. What the gates whose signal is \"you proved something, now act on it\"
  should read: `has-confirmed?` cannot tell a lemma from a check of the
  harness's own tooling, and both of those gates spend something real — a
  milestone nudge, or a fork."
  [branch]
  (boolean (some #(advances-thesis? branch (:claim %))
                 (confirmed-artifacts branch))))

(def verification-tools
  "Tools that actually put a claim in front of an engine.

  Used to clear the consecutive-search counter: a branch that has been
  searching has to TRY something, and a failed attempt counts — it tells the
  branch which step is hard, which another search does not.

  Also the set the forced reframe scopes over (vf-9wx). That one is right to
  use the WIDE set, unlike the explore phase below: what it withholds is a
  claim rather than a path, an approach can be ground in Z3 as easily as in
  Lean, and its substitute — verify something different — is available on
  every engine here."
  #{"verify" "verify_smt" "verify_lean" "verify_octave" "verify_template"
    "proof_start" "proof_step" "measure" "octave_eval"})

(def refusable-verification-tools
  "The verification tools a reframe can actually refuse: the ones that carry a
  CLAIM.

  `reframe-refusal` withholds a claim, not a tool, so a call with no claim
  cannot be declined however long the reframe stands. `octave_eval` is the
  only such tool — `measure` takes a claim, and `proof_step` inherits the open
  proof's, which reframe-refusal reads explicitly.

  This exists because the stuck gate's settle credits compliance when a
  verification call gets through while the reframe stands, on the reasoning
  that the withheld claim would have been refused so anything accepted must be
  a different one. That reasoning is sound for a refusable call and vacuous
  for the rest. tools.clj carried the hole as a comment — \"known soft spot:
  octave_eval carries no claim and is never refused, so it reads as compliance
  on its own\" — and gen-35 B1 then settled met twice on precisely it, turns
  50 and 52, each a bare octave_eval with no progress, no retraction and no
  change of technique, while it reworded the withheld claim and retried it
  (vf-7uy).

  The general principle, and the one the agent-evaluation literature states
  directly: never credit a step that could not have failed."
  (disj verification-tools "octave_eval"))

(def lean-verification-tools
  "The subset of `verification-tools` that a Lean sketch can stand in for.

  What the explore phase withholds (vf-b25), which is deliberately NARROWER
  than `verification-tools`. The only way out of explore is to bank a sketch,
  and `sketch` is Lean only — it lints its argument as Lean and requires it to
  elaborate with open sorries. Withholding Prolog, Z3 and Octave as well would
  ask a problem with no Lean content for a move it cannot make: refused, told
  to sketch, unable to, and burning the whole explore budget before the cap
  releases it. Nine of the fifteen bench problems are prolog/smt only,
  including knights-3, whose own note calls it the floor that catches
  regressions, and the odd-covering campaign was entirely Z3 and Prolog.

  So the phase gates the path its plan is written in. That is also what the
  literature separates: DSP and Hilbert are about drafting a proof for an
  interactive theorem prover, not about deferring a decision procedure.

  The wider set still exists and is still what clears the search counter — a
  branch that has been searching has to try SOMETHING, and any engine counts
  (vf-2vi)."
  #{"verify_lean" "proof_start" "proof_step"})

(defn enter-build
  "Transition the branch into :build, stamping the turn the phase began.

  A no-op when already there: the stamp means when THIS phase began, and the
  release valve's re-entry is reenter-explore's job (vf-9wx)."
  [branch turn]
  (if (= :build (:phase branch))
    branch
    (assoc branch :phase :build :phase-entered-turn turn)))

(defn reenter-explore
  "Drop the branch back into :explore and restart the phase clock.

  Nothing calls this yet — vf-9wx, the stuck-branch recovery, will. The
  restart is the point: the explore cap is per-entry, not per-branch, so a
  branch re-dropped into explore gets a fresh budget rather than being
  re-forced out on the next turn."
  [branch turn]
  (assoc branch :phase :explore :phase-entered-turn turn))

(defn enter-reframe
  "Withhold `claim` from this branch and start the reframe clock.

  vf-9wx. The harness's only answer to repeated failure was to kill the
  branch, which is a fine backstop and a poor first move: a branch that has
  been grinding one approach for three turns usually needs a different
  approach, not a funeral. The gate that fires here WITHHOLDS rather than
  suggests, because that is the one thing this harness has repeatedly measured
  as working — gen-27 ignored seventeen advisory nudges, while the audit
  gate's refusals had B4 rewriting its encoding three times.

  Claim-scoped rather than tool-scoped, which is what makes it work on every
  engine. The obvious implementation is to reuse the phase machine — drop the
  branch into :explore, where verification is unavailable and a plan is the
  only move — but :explore withholds only the LEAN tools, for the good reason
  that the way out of it is a Lean sketch (vf-2vi). On a Prolog or Z3 problem
  that withholds nothing and the gate is a no-op precisely where it is most
  needed: the odd-covering campaign is entirely Z3 and Prolog. Refusing the
  failing CLAIM bites on every engine, and its substitute — verify something
  else — is available to all of them."
  [branch turn claim]
  (assoc branch :reframe-claim claim :reframe-entered-turn turn))

(defn clear-reframe
  "End the reframe. The branch banked something, which the refused approach
  could not have produced, so the restriction and the reprieve both lift."
  [branch]
  (dissoc branch :reframe-claim :reframe-entered-turn))

(defn reframe-active?
  "Whether the branch is inside its reframe window.

  Keyed on the clock rather than on the claim, so a branch reframed with no
  identifiable claim to withhold still gets its turns to change course — it
  was told to change technique either way, and that costs turns either way.

  A nil `turn` means the caller is not tracking turns (a unit test, or a
  context that has no turn to give); the reframe then reads as active on the
  strength of the stamp alone."
  [branch turn grace]
  (boolean (and (:reframe-entered-turn branch)
                (or (nil? turn)
                    (< (- turn (:reframe-entered-turn branch)) grace)))))

(defn begin-reframe
  "Enter a reframe, and drop the branch back into :explore when — and only
  when — the approach that failed was a Lean one.

  The conditional is the vf-2vi rule applied in the other direction. A gate
  that withholds must not demand a move the branch cannot make, and `sketch`
  lints its argument as Lean and requires it to elaborate with open sorries.
  A branch failing on Prolog or Z3 has no Lean skeleton to write, so putting
  it in :explore would ask for one while withholding nothing it was using.
  For those branches the claim-scoped refusal is the whole mechanism, which is
  the design it should have been anyway.

  For a Lean branch the phase machine is worth the extra step: it reopens
  `sketch`, closes Lean verification until a NEW plan elaborates, and restarts
  the explore clock so the cap does not force it straight back out."
  [branch turn claim failing-tool]
  (cond-> (enter-reframe branch turn claim)
    (contains? lean-verification-tools failing-tool) (reenter-explore turn)))

(defn explore-cap-expired?
  "Whether the branch has spent more than `cap` turns in the current explore
  entry. The release valve: a branch that cannot get a skeleton to elaborate
  must not be locked out of verification for the whole run. Only :explore is
  capped — build has no clock, and reenter-explore restarts the count."
  [branch cap current-turn]
  (and (= :explore (:phase branch))
       (> (- current-turn (or (:phase-entered-turn branch)
                              (:created-at-turn branch)))
          cap)))

(defn record-outcome
  "Apply a turn's outcome to the counters the gates read.

  `progress?` is deliberately narrower than `success?`. A tool call can succeed
  and advance nothing — a model making varied, well-formed, useless calls trips
  no error-keyed guard and just burns the run, which is the case dirge PR 738's
  progress monitor exists for. And a confirmation that has nothing to do with
  the branch's own plan is the same failure wearing a success's clothes; see
  `advances-thesis?`.

  `consecutive-failures` drives the cull gate, so it has to mean consecutive.
  It used to be cleared only by :success — in practice only by banking an
  artifact — so a branch that hit a rough patch and then worked cleanly for
  several turns still carried the old count into the gate. gen-18 B1 made
  three malformed tool calls, recovered, ran three clean Octave sessions that
  produced dual potentials, and was culled on a counter last incremented three
  turns earlier. A clean turn now works one off the tally. Sustained failure
  still accumulates faster than recovery clears it, and the guard against
  well-formed but useless calls is `turns-since-progress`, which a neutral
  turn still increments — so nothing is given away here.

  `:mechanics` is a fourth category, for a turn that produced no usable tool
  call. It does NOT touch `consecutive-failures`: gen-20 B2 was culled at turn
  6 having called `thesis` and `lean_search` and nothing else, its four other
  turns having emitted no ```tool-call block, and the reason it died with said
  the critic had scored its line a dead end — when the branch had never made a
  claim for the critic to score. loop.clj draws exactly this distinction one
  branch up for a provider error; a fence the model malformed is the same kind
  of fault, and the branch already tracks mechanics separately.

  It gets its own tally rather than simply not counting, because separating
  the counter is the point and softening it would be a different bug: the
  `mechanics` map feeds only the capability tier, and `turns-since-progress`
  feeds only progress-stalled, so with neither counter moving, a branch
  emitting nothing but garbage would hold a beam slot to the turn budget. Any
  well-formed call clears the tally — the branch has demonstrated it can work
  the protocol, whatever the call then did."
  [branch {:keys [category progress? claim tool policy-refusal?]}]
  (let [real-progress? (and progress?
                            (or (nil? claim) (advances-thesis? branch claim)))]
    (cond-> branch
      (= :failure category) (update :consecutive-failures inc)
      ;; What the stuck gate withholds, and what it withholds it from. Only
      ;; failures set it: a branch that failed on A and then succeeded on B
      ;; has not been told to abandon anything. Left alone on a claimless
      ;; failure rather than cleared, because the last claim that DID fail is
      ;; still the better answer to "what is this branch grinding".
      ;; Not for Lean. Lean has no refuting outcome — it rejects PROOFS, never
      ;; claims — which is why verify_lean deliberately declines to record
      ;; :refuted for a snippet Lean throws out. The same reasoning has to
      ;; reach this counter: an unknown constant or an unsolved goal is
      ;; evidence about the proof, not about whether the statement is true, and
      ;; withholding the claim on it tells a branch to abandon something it was
      ;; right about. gen-31 B1 was the only branch attempting the run's target
      ;; and lost it to a mistyped lemma name.
      ;;
      ;; A Lean branch is still redirected: begin-reframe re-enters :explore on
      ;; a Lean failure, so verification stays shut until a NEW sketch
      ;; elaborates. That is the right response to a proof that will not go
      ;; through — try a different proof — and it needs no claim to withhold.
      ;; The tool is still recorded, because that is what chooses the phase.
      (and (= :failure category) (seq (str claim))
           (not (contains? lean-verification-tools tool)))
      (assoc :last-failed-claim claim)
      (= :failure category) (assoc :last-failed-tool tool)
      ;; Cleared by a success, so the gate can never withhold something the
      ;; branch has already got past. Without this, a branch that failed on A,
      ;; succeeded, and then failed twice on CLAIMLESS calls (octave_eval is
      ;; the one verification tool with no claim) would be refused A — an
      ;; approach it is not even working on. A false withholding blocks
      ;; legitimate work and says nothing about why.
      (= :success category)
      (assoc :last-failed-claim nil :last-failed-tool nil)
      (= :success category) (assoc :consecutive-failures 0)
      (= :neutral category) (update :consecutive-failures #(max 0 (dec (or % 0))))
      (= :mechanics category)
      (update :consecutive-mechanics-failures (fnil inc 0))
      (and (= :mechanics category) policy-refusal?)
      (update :consecutive-policy-refusals (fnil inc 0))
      (contains? #{:failure :success :neutral} category)
      (assoc :consecutive-mechanics-failures 0)
      (contains? #{:failure :success :neutral} category)
      (assoc :consecutive-policy-refusals 0)
      real-progress? (assoc :turns-since-progress 0 :any-progress? true)
      (not real-progress?) (update :turns-since-progress inc))))

(defn add-artifact [branch artifact]
  (update branch :artifacts conj artifact))

(defn add-turn
  "Record one turn on the branch. `entry` carries :turn, :tool, :category, and
  for a failure the :error it produced — the last so `repeating-failure?` can
  tell a branch stuck in a loop from one making fresh mistakes."
  [branch entry]
  (update branch :turns conj entry))

(defn repeating-failure?
  "Whether this branch's LAST turn was already this exact (tool, error) failure.

  29 of gen-20's 57 failures were four identical (tool, message) pairs, and the
  harness answered the fifth the way it answered the first. B1 — which had
  independently rediscovered the greedy characterisation, the best idea in the
  run — died calling `proof_start` wrong, being told, and calling it wrong
  again.

  Exact comparison rather than text similarity, deliberately. Both halves are
  already recorded, it needs no threshold to tune, and it cannot fire on an
  honest retry: a branch that changed anything about the call produces a
  different error, and a branch that succeeded in between is not looping.

  Called AFTER the turn is recorded, so it asks whether the last two turns are
  the same failure — one failure is a mistake, two identical ones are a loop."
  [branch tool error]
  (let [turns (:turns branch)
        ;; :mechanics as well as :failure. A branch repeating an identical
        ;; malformed call is looping in exactly the sense this function was
        ;; written for, and matching only :failure made the harness answer the
        ;; fifth the way it answered the first — gen-31 B3 was told "Missing
        ;; required argument(s): query" five times while a parser bug ate the
        ;; argument it had supplied. Categories that reached an engine and
        ;; those that never left the harness both loop the same way.
        same? (fn [t] (and t
                           (#{:failure :mechanics} (:category t))
                           (= tool (:tool t))
                           (= error (:error t))))]
    (boolean (and (>= (count turns) 2)
                  (same? (peek turns))
                  (same? (peek (pop turns)))))))

(defn add-message [branch role content]
  (update branch :messages conj {:role role :content content}))

(defn turn-count [branch] (count (:turns branch)))

(defn describe
  "One line for logs and for the run summary."
  [branch]
  (str (:id branch) " " (name (:status branch))
       " turns=" (turn-count branch)
       " artifacts=" (count (:artifacts branch))
       " confirmed=" (count (confirmed-artifacts branch))
       (let [m (count (empirical-artifacts branch))]
         (when (pos? m) (str " measured=" m)))
       (when-let [r (:inactive-reason branch)] (str " (" r ")"))))

(defn residual
  "What this branch left outstanding. A run cut short by the turn cap should
  say what is unfinished rather than making a resume re-derive scope from the
  transcript — dirge PR 738's residual objectives."
  [branch]
  (let [{:keys [goal subClaims]} (:thesis branch)
        proved (set (map :claim (confirmed-artifacts branch)))
        outstanding (remove proved subClaims)]
    (when goal
      {:branch (:id branch)
       :goal goal
       :proved (vec (filter proved subClaims))
       :outstanding (vec outstanding)
       :best (some-> (last (confirmed-artifacts branch)) :claim)})))

(defn render-residual [r]
  (when r
    (str "- " (:branch r) " was proving: " (:goal r) "\n"
         (when (seq (:proved r))
           (str "    proved: " (str/join "; " (:proved r)) "\n"))
         (when (seq (:outstanding r))
           (str "    outstanding: " (str/join "; " (:outstanding r)) "\n"))
         (when (:best r) (str "    best confirmed: " (:best r) "\n")))))

(defn- artifact-substantiates
  "What an artifact's claim-status lets it substantiate. Only :confirmed
  artifacts may be presented as established; the existential, measured and
  ambiguous buckets get their own clearly-labeled sections, and anything else
  (refuted, unknown) substantiates nothing and never renders."
  [a]
  (cond
    (= :confirmed (:claim-status a)) :established
    (= :existential (:claim-status a)) :existential
    (= :empirical (:claim-status a)) :measured
    (= :ambiguous (:claim-status a)) :ambiguous
    :else :neither))

(defn build-residual-report
  "The honest progress report for a run that exhausted without shipping.

  Never ship nothing, never ship a lie — UCLA Track B's honesty mandate, made
  mechanical by the artifact requirement: only artifacts with :confirmed
  status may appear as established, and every other artifact is labeled for
  exactly what it does and does not substantiate. The report says on its face
  that it is a progress report, not a solution.

  Pure: the final branch states, the failure-log entries and the gate tally
  arrive as data, so this is testable with no model and no store."
  [{:keys [branches failures gate-tally]}]
  {:label (str "PROGRESS REPORT — not a solution. Nothing below is"
               " established unless an engine confirmed it.")
   :branches
   (mapv (fn [b]
           (let [{:keys [goal subClaims]} (:thesis b)
                 confirmed (confirmed-artifacts b)
                 proved (set (map :claim confirmed))
                 grouped (group-by artifact-substantiates (:artifacts b))
                 provenance #(mapv (fn [a] (select-keys a [:claim :kind :tier :turn])) %)
                 audit (:last-audit b)]
             (cond-> {:branch (:id b)
                      :goal goal
                      :outstanding (vec (remove proved subClaims))
                      :proved (vec (filter proved subClaims))
                      :established (provenance (get grouped :established))
                      :existential (provenance (get grouped :existential))
                      :measured (provenance (get grouped :measured))
                      :ambiguous (provenance (get grouped :ambiguous))}
               ;; Drift is only reportable when the audit actually restated
               ;; what the evidence establishes; an audit with no ESTABLISHED
               ;; line has nothing to compare against the goal.
               (:established audit)
               (assoc :drift {:goal goal
                              :established (:established audit)
                              :relaxation? (:relaxation? audit)}))))
         branches)
   :failures (vec failures)
   :gate-tally (vec gate-tally)})

(defn render-residual-report
  "Markdown-ish text for the API content slot. The established section is the
  load-bearing one; existential, ambiguous, drift and run-level sections are
  labeled for exactly what they are."
  [r]
  (when r
    (str (:label r) "\n\n"
         (str/join "\n\n"
                   (for [b (:branches r)]
                     (str (str "## " (:branch b)
                               (when (:goal b) (str " — was proving: " (:goal b))))
                          (when (seq (:outstanding b))
                            (str "\n\nOutstanding sub-claims (undischarged):\n"
                                 (str/join "\n" (map #(str "- " %) (:outstanding b)))))
                          (when (seq (:established b))
                            (str "\n\nEstablished (engine-confirmed):\n"
                                 (str/join "\n" (for [a (:established b)]
                                                  (str "- [" (:kind a) "/" (:tier a) "] " (:claim a))))))
                          (when (seq (:existential b))
                            (str "\n\nExistential only — the engine confirmed existence, not an instance:\n"
                                 (str/join "\n" (for [a (:existential b)]
                                                  (str "- [" (:kind a) "/" (:tier a) "] " (:claim a))))))
                          (when (seq (:measured b))
                            (str "\n\nMeasured — what a computation produced at the"
                                 " parameters it was run at, not a proof:\n"
                                 (str/join "\n" (for [a (:measured b)]
                                                  (str "- [" (:kind a) "/" (:tier a) "] " (:claim a))))))
                          (when (seq (:ambiguous b))
                            (str "\n\nAmbiguous — the engine returned no decisive verdict; substantiates nothing:\n"
                                 (str/join "\n" (for [a (:ambiguous b)]
                                                  (str "- [" (:kind a) "/" (:tier a) "] " (:claim a))))))
                          (when (:drift b)
                            (str "\n\nThesis drift — the last audit found the evidence establishes \""
                                 (:established (:drift b)) "\", "
                                 (if (:relaxation? (:drift b))
                                   "strictly weaker than the goal"
                                   "matching the goal")
                                 " \"" (:goal b) "\".")))))
         (when (seq (:failures r))
           (str "\n\n## Shared failure log (most recent first)\n"
                (str/join "\n" (for [f (:failures r)]
                                 (str "- [" (:branch_id f) " t" (:turn f) " " (:tool_name f) "] "
                                      (:claim f) "\n  → " (:reason f))))))
         (when (seq (:gate-tally r))
           (str "\n\n## Gate firings\n"
                (str/join "\n" (for [g (:gate-tally r)]
                                 (str "- " (:gate g) ": " (:fired g) " fired, "
                                      (or (:met g) 0) " met, " (or (:unmet g) 0) " unmet, "
                                      (or (:open g) 0) " open"))))))))

;; --- safe state -------------------------------------------------------------
;;
;; DS1's third failure rung, which dirge implemented as a git-backed restore.
;; The branch analogue is the Prolog session's ordered assert log at the moment
;; of the last confirmation. Restoring means replaying that log into a fresh
;; process, which is what `retract` alone cannot give: an anonymous assert has
;; no name to take back.

(defn mark-green
  "Record the session state at a confirmation. The snapshot is the replay log,
  not a copy of the process."
  [branch snapshot]
  (assoc branch :green-snapshot snapshot :green-at-turn (count (:turns branch))))

(defn snapshot-covers?
  "Whether restoring to the green point would produce a tree that ever existed.

  This is the coverage gate, and it is the part that matters. dirge's version
  declines when a `sed -i` mutated a file outside the snapshot store, because
  restoring around it yields a state that never was. The analogue here is an
  ANONYMOUS assert made since the green point: the replay log records it, but
  it is permanent and unnamed, so a branch that reached its current state
  partly through untracked mutation cannot be honestly rewound.

  Declining is the safe answer. A partially-restored session is worse than no
  restore, because it is a state nobody reasoned about."
  [branch current-log]
  (let [green (:green-snapshot branch)]
    (cond
      (nil? green) {:ok false :reason "no confirmed result to fall back to"}

      (not= green (take (count green) current-log))
      {:ok false :reason (str "the session log diverges from the snapshot before"
                              " the green point, so the snapshot is not a prefix"
                              " of the current state")}

      :else
      (let [since (drop (count green) current-log)
            untracked (remove :name since)]
        (if (seq untracked)
          {:ok false
           :reason (str (count untracked) " anonymous assert(s) since the last"
                        " confirmation. They are permanent and unnamed, so"
                        " rewinding would produce a session that never existed.")}
          {:ok true :rewinding (count since)})))))

(defn safe-state-due?
  "Twice the cull threshold's worth of consecutive failures, with a green point
  to fall back to. Deliberately harder to trigger than a cull: this rung spends
  a hard-capped abort and rebuilds a process."
  [branch cull-threshold]
  (and (:green-snapshot branch)
       (>= (:consecutive-failures branch) (* 2 cull-threshold))))
