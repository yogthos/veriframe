;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.resume
  "Resume a crashed run by replaying its journal.

  UCLA's recovery is event-sourced replay plus a persisted run-start anchor,
  so a restart does not re-grant the budget. The anchor here is the turns
  table and the runs row: a resumed run continues at (last recorded turn)+1
  against the run's ORIGINAL max_turns, so a crash at hour N does not buy N
  more hours. state.clj promises that everything a gate needs is journalled;
  this namespace is what makes the promise true.

  Fidelity contract, per field:

  REPLAYS EXACTLY, from tables verbatim:
  - branch status, inactive-reason, created-at-turn, and thesis (branches row).
  - artifacts with claim-status / verdict / witness (artifacts rows).
  - failures are re-read fresh by the loop from the shared log, as always.
  - message history from the turns table: assistant_text is what the model
    said, result is what the harness answered, over the system prompt and the
    run's recorded problem (loop/initial-messages). Steer messages that fired
    pre-crash are not in the journal, so they are not replayed; that is good
    enough for the model to continue and is the accepted fidelity gap.

  REPLAYS CONSERVATIVELY, recomputed so no guard re-fires on its own past:
  - consecutive-failures, turns-since-progress, any-progress? from the turns
    category column through state/record-outcome, the same function the live
    loop uses. progress? is approximated as (= category :success) because the
    tool's own progress? flag is not journalled.
  - gate-history and open-predictions from gate_firings rows, so a gate that
    fired pre-crash counts against its budget after resume and unsettled
    predictions still settle at the next boundary.
  - notified-fractions from the rebuilt turn count via gates/crossed-fractions,
    so the turn-budget notice does not re-fire on fractions already crossed.
  - tiers-seen from rebuilt artifact tiers.
  - mechanics from the turns parse_error / auto_repaired columns; fence
    signals that are not journalled (truncation, multi-fence) reset to zero.

  DOES NOT REPLAY (known limits, accepted):
  - Lean: a resumed branch gets no Lean session and its Lean tools report the
    session unavailable. Lean sessions are process memory with a heavy startup
    cost; a crash cannot resurrect them.
  - The safe-state green snapshot is branch memory, not journalled (mark-green
    assocs into the branch map only), so a resumed branch starts a fresh empty
    Prolog session and the safe-state rung has no fallback until its next
    confirmation. In-process safe-state aborts are unaffected.
  - last-review / last-audit are branch memory; the done gate re-requires
    review/audit after a resume rather than trusting pre-crash state.
  - The claim registry is run-scoped memory; resume starts it empty, safe
    because the worst case is one duplicate slow verification.
  - :shared-served (which shared artifacts a branch was already told about)
    is branch memory; resume starts it empty, so each artifact-branch pair
    can journal one duplicate shared-artifact-hit after a resume.
  - :critic scores and :fork-invited markers are branch memory; a resumed
    branch is re-scored at its next boundary, and may be re-invited to fork
    sooner than the cooldown would otherwise allow.
  - the per-turn :error that repeating-failure? compares is not replayed, so a
    branch that was looping on one identical failure gets one un-escalated
    answer after a resume before the harness can see the loop again. The turns
    table does hold the result text, but it holds the ESCALATED copy, which
    would not compare equal to the next clean one — replaying it would break
    the detection it was meant to restore."
  (:require [clojure.data.json :as json]
            [veriframe.agent.beam :as beam]
            [veriframe.agent.claims :as claims]
            [veriframe.agent.gates :as gates]
            [veriframe.agent.loop :as branch-loop]
            [veriframe.agent.state :as state]
            [veriframe.engine.prolog :as prolog]
            [veriframe.store.journal :as journal]
            [veriframe.store.runs :as runs]))

(defn- parse-json [s]
  (when s
    (try (json/read-str s :key-fn keyword) (catch Throwable _ s))))

(defn- artifact-map
  "An artifacts row back into the map the tools conj onto the branch."
  [a]
  {:turn (:turn a)
   :kind (keyword (:kind a))
   :claim (:claim a)
   :code (:code a)
   :verdict (some-> (:verdict a) keyword)
   :witness (parse-json (:witness a))
   :claim-status (keyword (:claim_status a))
   :tier (keyword (:tier a))})

(defn- messages-from-turns
  "The message history a continuing model needs: what it said, and what the
  harness answered, over the system prompt and the problem."
  [problem turns]
  (reduce (fn [msgs t]
            (cond-> msgs
              (seq (:assistant_text t))
              (conj {:role "assistant" :content (:assistant_text t)})
              (seq (:result t))
              (conj {:role "user" :content (:result t)})))
          (branch-loop/initial-messages problem)
          turns))

(defn- rebuild-branch
  "Reconstruct one branch's working map from the journal rows for its run.

  `session` is the fresh empty Prolog session the branch will run on; the
  green snapshot is not journalled, so this is always the 'otherwise' branch
  of the safe-state rule."
  [run branch-row turns artifacts firings max-turns session]
  (let [branch-id (:id branch-row)
        branch-turns (get turns branch-id [])
        base (-> (state/new-branch {:id branch-id
                                    :parent-id (:parent_id branch-row)
                                    :problem (:problem run)
                                    :prolog session
                                    :created-at-turn (:created_at_turn branch-row)
                                    :messages (messages-from-turns (:problem run)
                                                                   branch-turns)})
                 (assoc :status (keyword (:status branch-row))
                        :inactive-reason (:inactive_reason branch-row)
                        :thesis (parse-json (:thesis branch-row))
                        :artifacts (mapv artifact-map (get artifacts branch-id []))
                        :gate-history (mapv (fn [f]
                                              {:gate (keyword (:gate f))
                                               :turn (:turn f)})
                                            (get firings branch-id []))
                        :open-predictions (mapv (fn [f]
                                                  {:id (:id f)
                                                   :gate (keyword (:gate f))
                                                   :prediction (:prediction f)
                                                   :window (:window f)
                                                   :turn (:turn f)})
                                                (remove #(:outcome %)
                                                        (get firings branch-id [])))))
        ;; The counters, replayed through the same function the live loop
        ;; uses. progress? is approximated from the category because the
        ;; tool's own progress? flag is not journalled.
        branch (reduce (fn [b t]
                         (let [cat (some-> (:category t) keyword)]
                           (-> b
                               (state/add-turn {:turn (:turn t)
                                                :tool (:tool_name t)
                                                :category cat})
                               (state/record-outcome {:category cat
                                                      :progress? (= :success cat)}))))
                       base
                       branch-turns)
        branch (assoc branch
                      :tiers-seen (set (keep :tier (:artifacts branch)))
                      :mechanics {:calls (count branch-turns)
                                  :parse-errors (count (filter :parse_error branch-turns))
                                  :auto-repairs (count (filter #(pos? (:auto_repaired %))
                                                               branch-turns))
                                  :unknown-tools 0 :truncations 0 :multi-fences 0}
                      ;; The fractions already crossed are told, so the
                      ;; turn-budget notice does not re-fire on them.
                      :notified-fractions (gates/crossed-fractions branch max-turns))]
    branch))

(defn resumable?
  "A run can be resumed when it exists and has not reached a terminal state.

  An aborted run STAYS aborted: abort is a person saying stop, and a resume is
  not a person changing their mind. A completed run shipped; the answer is the
  record. Only a run still in flight — status 'running', or 'failed' from an
  exhausted process that never got to tear down — is resumable."
  [conn run-id]
  (when-let [r (runs/get-run conn run-id)]
    (not (contains? #{"completed" "aborted"} (:status r)))))

(defn resume!
  "Rebuild a run's branches from the journal and continue the beam's round
  loop at the round after the last recorded turn, under the run's ORIGINAL
  max-turns.

  `:max-turns` is the one exception to the anchor rule, and it is explicit:
  when passed AND larger than the recorded budget, the runs row is raised to
  it and branches closed as `exhausted` reopen — they closed because the
  budget ran out, not for cause, so more budget un-closes them. Culled,
  abandoned, and done branches stay closed. The extension is journaled
  (budget-extended, branch-reopened) and the rows are updated BEFORE the
  branches are read back, so a crash mid-extension replays correctly. A
  crash still cannot re-grant budget; only a caller asking for more can.

  Pending interventions are already in their table; the existing
  pending-directives drain picks them up at the first resumed boundary — this
  function does not reimplement that path.

  Returns the beam/run-rounds result. Throws when the run is not resumable."
  [{:keys [conn config llm-adapter llm-config run-id abort max-turns]}]
  (let [run (runs/get-run conn run-id)]
    (when-not (resumable? conn run-id)
      (throw (ex-info (str "run " run-id " is not resumable (status "
                           (:status run) ")")
                      {:run-id run-id :status (:status run)})))
    ;; The row said 'interrupted' (or 'failed'); it is about to be running
    ;; again, and stalled? only watches runs whose status says so.
    (runs/mark-running! conn run-id)
    (when (and max-turns (> max-turns (:max_turns run)))
      (runs/extend-budget! conn run-id max-turns)
      (doseq [b (runs/branches conn run-id)
              :when (= "exhausted" (:status b))]
        (runs/reopen-branch! conn run-id (:id b))))
    (let [max-turns (max (or max-turns 0) (:max_turns run))
          width (:beam_width run)
          turn-rows (journal/turns conn run-id)
          turns (group-by :branch_id turn-rows)
          artifacts (group-by :branch_id (journal/artifacts conn run-id))
          firings (group-by :branch_id (journal/gate-firings conn run-id))
          sessions (atom [])
          ctx {:conn conn :run-id run-id :config config :problem (:problem run)
               :llm-adapter llm-adapter :llm-config llm-config
               :max-turns max-turns :beam? (> width 1) :beam-width width
               :sessions sessions
               :abort abort
               ;; One claim registry per run: two branches reaching the same
               ;; claim share one slow verification instead of racing it.
               :claims (claims/new-registry)}
          branches (mapv (fn [row]
                           (let [session (prolog/create-session
                                          (get-in config [:engines :swipl]))]
                             (swap! sessions conj session)
                             (rebuild-branch run row turns artifacts firings
                                             max-turns session)))
                         (runs/branches conn run-id))
          ;; The anchor: rounds completed are the max turn in the journal, so
          ;; the loop continues one past it. max-turns is the ORIGINAL budget.
          start-turn (inc (reduce max 0 (map :turn turn-rows)))]
      (beam/run-rounds ctx branches start-turn))))
