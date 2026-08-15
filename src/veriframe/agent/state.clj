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
  branch which step is hard, which another search does not."
  #{"verify" "verify_smt" "verify_lean" "verify_octave" "verify_template"
    "proof_start" "proof_step" "measure" "octave_eval"})

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
  [branch {:keys [category progress? claim]}]
  (let [real-progress? (and progress?
                            (or (nil? claim) (advances-thesis? branch claim)))]
    (cond-> branch
      (= :failure category) (update :consecutive-failures inc)
      (= :success category) (assoc :consecutive-failures 0)
      (= :neutral category) (update :consecutive-failures #(max 0 (dec (or % 0))))
      (= :mechanics category)
      (update :consecutive-mechanics-failures (fnil inc 0))
      (contains? #{:failure :success :neutral} category)
      (assoc :consecutive-mechanics-failures 0)
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
        same? (fn [t] (and t
                           (= :failure (:category t))
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
