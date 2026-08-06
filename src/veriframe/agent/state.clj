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

(defn confirmed-in-last
  "Whether a confirmed artifact landed within the last `n` turns. Incremental
  strategies naturally look like verify size N, fail at N+1, verify N+1, and
  culling them throws away the most valuable branch."
  [branch n]
  (let [cutoff (- (count (:turns branch)) n)]
    (boolean (some #(and (= :confirmed (:claim-status %))
                         (>= (:turn %) cutoff))
                   (:artifacts branch)))))

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

(defn- claim-tokens [s]
  (->> (str/split (str/lower-case (or s "")) #"[^a-z0-9]+")
       (remove str/blank?)
       (remove claim-stopwords)
       (filter #(>= (count %) 4))
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

  With no thesis registered any confirmation counts, because there is nothing
  to measure against and refusing to credit exploration would be worse."
  [branch claim]
  (let [sub-claims (get-in branch [:thesis :subClaims])
        goal (get-in branch [:thesis :goal])]
    (if (empty? (remove str/blank? (conj (vec sub-claims) goal)))
      true
      (let [ct (claim-tokens claim)]
        (boolean (some #(seq (clojure.set/intersection ct (claim-tokens %)))
                       (conj (vec sub-claims) goal)))))))

(defn record-outcome
  "Apply a turn's outcome to the counters the gates read.

  `progress?` is deliberately narrower than `success?`. A tool call can succeed
  and advance nothing — a model making varied, well-formed, useless calls trips
  no error-keyed guard and just burns the run, which is the case dirge PR 738's
  progress monitor exists for. And a confirmation that has nothing to do with
  the branch's own plan is the same failure wearing a success's clothes; see
  `advances-thesis?`."
  [branch {:keys [category progress? claim]}]
  (let [real-progress? (and progress?
                            (or (nil? claim) (advances-thesis? branch claim)))]
    (cond-> branch
      (= :failure category) (update :consecutive-failures inc)
      (= :success category) (assoc :consecutive-failures 0)
      real-progress? (assoc :turns-since-progress 0 :any-progress? true)
      (not real-progress?) (update :turns-since-progress inc))))

(defn add-artifact [branch artifact]
  (update branch :artifacts conj artifact))

(defn add-turn [branch entry]
  (update branch :turns conj entry))

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
  artifacts may be presented as established; the existential and ambiguous
  buckets get their own clearly-labeled sections, and anything else (refuted,
  unknown) substantiates nothing and never renders."
  [a]
  (cond
    (= :confirmed (:claim-status a)) :established
    (= :existential (:claim-status a)) :existential
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
