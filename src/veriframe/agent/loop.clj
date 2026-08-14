;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.loop
  "The branch loop: one turn is one model call, one tool call, one arbiter
  decision, and a journal append.

  Phase 3 runs a single branch. The beam in Phase 4 schedules many of these;
  nothing here assumes it is alone, which is why every write already carries a
  branch id.

  The order inside a turn is load-bearing. The tool runs before the arbiter, so
  a gate sees the state the turn produced rather than the state it started
  from. Predictions settle before new gates fire, so a gate cannot be credited
  with an outcome that preceded it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [veriframe.agent.arbiter :as arbiter]
            [veriframe.agent.gates :as gates]
            [veriframe.agent.state :as state]
            [veriframe.agent.tools :as tools]
            [veriframe.engine.prolog :as prolog]
            [veriframe.engine.smt-templates :as templates]
            [veriframe.llm.client :as llm]
            [veriframe.llm.fence :as fence]
            [veriframe.llm.message :as message]
            [veriframe.store.artifacts :as artifacts]
            [veriframe.store.failures :as failures]
            [veriframe.store.journal :as journal]
            [veriframe.store.runs :as runs])
  (:refer-clojure :exclude [run!]))

(def max-result-chars 4000)

(defn system-prompt
  "The system prompt, with the template catalogue substituted in.

  The catalogue is generated rather than written into the file because it is
  pure data and would otherwise drift: before this, the only way the model
  learned which templates exist was to guess a name and read the list off the
  error, which meant a template it had not guessed was effectively invisible.

  The tool documentation IS hand written, because a prompt is prose and
  generated prose reads like it. `veriframe.prompt-test` asserts every name in
  `tools/tool-names` appears here, so a new tool cannot be added without being
  documented — that is what kept the whole Lean surface unreachable."
  []
  (-> (slurp (io/resource "prompts/system.md"))
      (str/replace "{{templates}}" (templates/list-templates))))

(defn judge-exemptions
  "The DO-NOT-FLAG list shipped to the audit and review judges. A var rather
  than a slurp inline so the digest can be attributable to it; re-read per
  digest, which is per run."
  []
  (slurp (io/resource "prompts/judge-exemptions.md")))

(defn prompt-digest
  "A cheap fingerprint of the prompt and gate set a run used. AHE component
  observability: a pass-rate change should be attributable to a file."
  []
  (str (hash [(system-prompt) (gates/config) (judge-exemptions)])))

(defn shareable?
  "Whether a just-produced artifact belongs in the run's shared pool.

  Engine-confirmed is the entry condition that separates this from UCLA's
  self-reported results, and relevance is the second one. An artifact that
  engages neither the branch's thesis nor the problem cost its own branch a
  turn and nothing more; exported, it becomes every branch's context. Run
  0d0c3560 shipped `Diagnostic: between(-1,1,X) succeeds for X = -1,0,1.` to
  three siblings four turns in (vf-8fl)."
  [branch artifact share?]
  (boolean (and share?
                (= :confirmed (:claim-status artifact))
                (state/advances-thesis? branch (:claim artifact)))))

(defn- truncate [s]
  (let [s (str s)]
    (if (> (count s) max-result-chars)
      (str (subs s 0 max-result-chars) "\n… [truncated]")
      s)))

(defn initial-messages [problem]
  [{:role "system" :content (system-prompt)}
   {:role "user" :content (str "## Problem\n\n" problem "\n\nIssue your first tool call.")}])

(defn- context-block
  "What the harness adds to the branch's view before its next turn: the
  failures most like what it just tried, and — when sharing is on — the
  artifacts other branches confirmed that look most like it. Both FTS-ranked
  rather than the whole log, so the block stays small and stays relevant.

  Own-branch entries are excluded from both: a branch re-reading its own
  lemmas is noise, and the value of sharing is exactly the cross-branch hit.
  A shared artifact is journaled the FIRST time it enters this branch's
  context and never again: the block re-renders every turn, so per-serving
  events counted turns (86 events for a 15-row pool on one 28-turn run), not
  distinct sharing. Whether sharing earns the beam its width stays a question
  the journal can answer, now directly. Returns {:block :branch}; the branch
  carries the :shared-served ids the dedup reads."
  [conn run-id branch last-claim share?]
  (let [others #(remove (fn [e] (= (:branch_id e) (:id branch))) %)
        fhits (others (if (str/blank? last-claim)
                        (failures/recent conn run-id 5)
                        (failures/similar conn run-id last-claim 5)))
        ;; Fetched WIDE and then ranked down, not fetched at the display size.
        ;; Ranking a top-5 that is already all seeds just reorders seeds: a
        ;; completed run contributes its whole pool at turn 0 while the live
        ;; run's starts empty, and gen-20 served 67 seeded artifacts to 24 of
        ;; its own. Taking a wider slice is what lets an in-run lemma reach
        ;; the block at all; prefer-in-run then decides the order.
        shared-shown 5
        ahits (when share?
                (->> (if (str/blank? last-claim)
                       (artifacts/recent conn run-id (* 3 shared-shown))
                       (artifacts/similar conn run-id last-claim (* 3 shared-shown)))
                     others
                     artifacts/prefer-in-run
                     (take shared-shown)
                     vec))
        fresh (remove (comp (or (:shared-served branch) #{}) :id) ahits)]
    (doseq [a fresh]
      (journal/note! conn run-id :shared-artifact-hit
                     {:branch-id (:id branch)
                      :data {:claim (:claim a) :source-branch (:branch_id a)}}))
    (let [blocks (keep identity [;; The run's settled state, first and complete:
                                 ;; what is established and — the half nothing
                                 ;; carried before — what is RULED OUT. Read
                                 ;; from the artifacts table every turn, so it
                                 ;; cannot drift from the record, and cheap:
                                 ;; gen-20's whole confirmed set is under 400
                                 ;; tokens of claim text. Unlike the blocks
                                 ;; below it is not FTS-sampled, because the
                                 ;; value of a ledger is that a branch can
                                 ;; trust the absence of a line.
                                 (artifacts/render-ledger
                                  (journal/ledger conn run-id))
                                 (failures/render fhits)
                                 (artifacts/render ahits)])]
      {:block (when (seq blocks) (str/join "\n\n" blocks))
       :branch (update branch :shared-served (fnil into #{}) (map :id fresh))})))

;; --- one turn ---------------------------------------------------------------

(def ^:private max-call-attempts
  "One retry, then the turn is spent. Unbounded escalation here would let a
  single turn eat a branch's whole budget, and a model that has not reached a
  tool call in twice its cap is not one token short."
  2)

(defn- truncated-without-call?
  "The response ran out of tokens before it emitted a usable tool call.

  fence/signals already separates this from `:no-fence` and its docstring says
  what to do about it — 'the fix is more tokens, not more steering' — but the
  loop steered anyway and forfeited the turn. gen-12 opened with three of these
  in a single round; gen-11 spent 12% of its turns this way against gen-10's
  4%. Truncation that still carried a call is a complete turn and is left
  alone.

  Takes the prefill for the same reason the parser does: a prefilled response
  begins mid-fence, so parsing it without the opener finds no call and would
  bill the branch a retry for a turn that had in fact issued one."
  [response prefill]
  (let [parsed (fence/parse-tool-call (:content response) {:prefill prefill})]
    (and (:truncated (fence/signals response parsed))
         (or (nil? parsed) (= "__parse_error__" (:name parsed))))))

(defn- call-model
  "One model call, retried once at a doubled budget when the first response hit
  the token cap before emitting a tool call.

  Same sizing as the judge's: double the configured budget rather than repeat
  it, since a response that ran out of room needs room, and repeating the call
  at the same cap reproduces the same truncation."
  [ctx branch]
  (loop [attempt 1]
    (let [budget (when-let [base (:max-tokens (:llm-config ctx))]
                   (* base (bit-shift-left 1 (dec attempt))))
          r (try
              {:ok true
               :response (llm/chat (:llm-adapter ctx) (:llm-config ctx)
                                   ;; Older turns go as a digest of what they
                                   ;; tried once the history is long; the
                                   ;; branch's own message list is untouched,
                                   ;; so the journal and a resume still hold
                                   ;; everything. Below the threshold this
                                   ;; returns the messages unchanged.
                                   (message/compact (:messages branch)
                                                    (:turns branch))
                                   (cond-> {}
                                     budget (assoc :max-tokens budget)
                                     ;; Set by the previous turn's steer. The
                                     ;; adapter drops it if the provider cannot
                                     ;; continue a trailing assistant message,
                                     ;; so this is a hint, never a requirement.
                                     (:prefill branch)
                                     (assoc :prefill (:prefill branch))))}
              (catch Throwable e
                {:ok false :error (ex-message e)}))]
      (if (and (:ok r)
               (< attempt max-call-attempts)
               (truncated-without-call? (:response r) (:prefill branch)))
        (do (when (and (:conn ctx) (:run-id ctx))
              (journal/note! (:conn ctx) (:run-id ctx) :turn-retry
                             {:branch-id (:id branch)
                              :data {:reason "truncated before any tool call"
                                     :budget budget}}))
            (recur (inc attempt)))
        r))))

(defn- settle-predictions!
  "Close out any prediction whose window has passed or whose expectation the
  branch just met. Deterministic; no model in the path."
  [conn branch turn tools-called before after]
  (let [{kept true closed false}
        (group-by (fn [p]
                    (nil? (arbiter/settle p {:current-turn turn
                                             :tools-called tools-called
                                             :branch-before before
                                             :branch-after after})))
                  (:open-predictions after))]
    (doseq [p closed]
      (journal/settle-gate! conn (:id p)
                            (arbiter/settle p {:current-turn turn
                                               :tools-called tools-called
                                               :branch-before before
                                               :branch-after after})
                            turn))
    (assoc after :open-predictions (vec kept))))

(defn run-turn
  "Advance one branch by one turn. Returns the updated branch."
  [{:keys [conn run-id max-turns] :as ctx} branch turn]
  (let [before branch
        {:keys [ok response error]} (call-model ctx branch)]
    (if-not ok
      ;; A provider failure is not the branch's fault and must not count
      ;; against it as a verification failure.
      (do (log/warn "branch" (:id branch) "turn" turn "model call failed:" error)
          (journal/record-turn! conn run-id
                                {:branch-id (:id branch) :turn turn
                                 :tool-name "__provider_error__" :result error
                                 :category "neutral"})
          (state/add-message branch "user"
                             (str "[harness] The provider call failed: " error
                                  " Try again.")))
      (let [content (:content response)
            ;; The prefill the request ended with, if any. Without it the
            ;; response starts mid-fence and parses as a no-call — the very
            ;; failure the prefill exists to prevent.
            prefill (:prefill branch)
            parsed (fence/parse-tool-call content {:prefill prefill})
            signals (fence/signals response parsed)
            ;; What the assistant actually said, opener included. Storing the
            ;; bare completion would leave a turn beginning mid-fence in the
            ;; transcript, misrepresenting the format back to the model on
            ;; every later turn.
            said (fence/reattach content prefill)
            branch (-> branch
                       ;; Cleared here, not where it was set: one steer
                       ;; forecloses prose on one turn. Leaving it would make
                       ;; every later turn start inside a fence.
                       (dissoc :prefill)
                       (state/add-message "assistant" said)
                       (state/record-mechanics signals))]
        (if (or (nil? parsed) (= "__parse_error__" (:name parsed)))
          ;; No usable call. Say exactly what was wrong; a bare "try again"
          ;; produces another identical attempt.
          (let [msg (cond
                      (:truncated signals)
                      (str "[harness] Your response hit the token limit before you"
                           " emitted a tool call. Think less and call a tool.")
                      (nil? parsed)
                      (str "[harness] No ```tool-call block in your response."
                           " Every turn must end with exactly one.")
                      :else
                      (str "[harness] Your tool-call block did not parse: "
                           (:parse-error parsed)))]
            ;; The response matters most on THIS path. A turn that produced no
            ;; usable call records nothing else about what the model did, and
            ;; without the text there is no way to tell a model that rambled
            ;; from one that emitted the wrong fence from one that answered in
            ;; prose. Nine of twenty turns in a Lean run landed here and the
            ;; question was unanswerable.
            ;; `mechanics`, not `failure`. The branch produced no claim, so
            ;; there is nothing here to hold against its line of inquiry —
            ;; the same reasoning as the provider-error path above. gen-20 B2
            ;; was culled at turn 6 for four malformed fences, having called
            ;; only `thesis` and `lean_search`, and the cull reason blamed the
            ;; critic for scoring a line the critic had never seen. The count
            ;; is still kept and still bounds the branch; see record-outcome.
            (journal/record-turn! conn run-id
                                  {:branch-id (:id branch) :turn turn
                                   :tool-name (or (:name parsed) "__no_call__")
                                   :result msg :category "mechanics"
                                   :parse-error (:parse-error parsed)
                                   :auto-repaired (:auto-repaired? parsed)
                                   :assistant-text said
                                   :reasoning-text (:reasoning response)
                                   ;; A turn that produced no usable call still
                                   ;; cost tokens, and those are the ones worth
                                   ;; counting — a branch looping on malformed
                                   ;; fences is spend with nothing to show.
                                   :usage (:usage response)})
            (-> branch
                (state/record-outcome {:category :mechanics :progress? false})
                (state/add-message "user" msg)
                ;; And make the next request end mid-fence, so prose is not an
                ;; available reply. Telling the model to emit a fence is the
                ;; suggesting form; this is the withholding form, which is the
                ;; one that has ever worked — see arbiter/prefill-for.
                ;;
                ;; gen-22 B1 was sent the message above twenty-four times in
                ;; forty-four turns and answered in prose every time, once at
                ;; 109,360 characters against a 32,768-token cap. A branch
                ;; that cannot reach a tool is not thinking; it is idling at
                ;; full spend.
                ;;
                ;; Bare, with no tool named: a gate that knows which tool it
                ;; wants supplies the name itself, but here nothing is being
                ;; steered — the branch had a plan and failed to act on it,
                ;; and picking its next call for it would replace a mechanics
                ;; failure with the harness doing the reasoning.
                (assoc :prefill "```tool-call\n")))

          ;; A real tool call.
          (let [tool (:name parsed)
                result (tools/run-tool (assoc ctx :branch branch :turn turn
                                              :tool-name tool :args (:args parsed)))
                branch (-> (:branch result)
                           (state/record-outcome (assoc result :claim (get-in parsed [:args :claim])))
                           (state/add-turn {:turn turn :tool tool
                                            :category (:category result)
                                            ;; Kept only for failures, and only
                                            ;; so repeating-failure? can see a
                                            ;; loop. Not a second copy of the
                                            ;; journal: the turns table holds
                                            ;; the authoritative result.
                                            :error (when (= :failure (:category result))
                                                     (str (:result result)))}))
                ;; 29 of gen-20's 57 failures were four identical (tool,
                ;; message) pairs, and the harness answered the fifth exactly
                ;; as it answered the first. Say something different instead —
                ;; the branch is going to spend the next turn regardless.
                result (if (state/repeating-failure? branch tool (str (:result result)))
                         (update result :result
                                 #(str % "\n\n[harness] This exact call has now"
                                       " failed this exact way more than once."
                                       " Repeating it will fail again. Change"
                                       " the call, or change technique — a"
                                       " different tool, a smaller claim, or a"
                                       " different encoding of the same one."))
                         result)
                branch (if-let [a (:artifact result)]
                         (state/add-artifact branch (assoc a :turn turn))
                         branch)
                ;; A confirmation is the green point the safe-state rung falls
                ;; back to. The snapshot is the session's replay log, not a
                ;; copy of the process.
                branch (if (and (= :confirmed (get-in result [:artifact :claim-status]))
                                (:prolog branch))
                         (state/mark-green branch (prolog/snapshot (:prolog branch)))
                         branch)]
            (journal/record-turn! conn run-id
                                  {:branch-id (:id branch) :turn turn
                                   :tool-name tool :args (:args parsed)
                                   :result (truncate (:result result))
                                   :category (name (:category result))
                                   :auto-repaired (:auto-repaired? parsed)
                                   :assistant-text said
                                   :reasoning-text (:reasoning response)
                                   :usage (:usage response)})
            (when-let [a (:artifact result)]
              (journal/record-artifact! conn run-id
                                        (assoc a :branch-id (:id branch) :turn turn))
              ;; Only engine-confirmed, on-topic artifacts enter the shared
              ;; pool — see shareable?. The flag is the diversity trade-off's
              ;; off switch.
              (when (shareable? branch a (get-in ctx [:config :run :share-artifacts?]))
                (artifacts/record! conn run-id
                                   {:branch-id (:id branch) :turn turn
                                    :kind (:kind a) :tier (:tier a)
                                    :claim (:claim a) :code (:code a)})))
            (when-let [f (:failure result)]
              (failures/record! conn run-id
                                (assoc f :branch-id (:id branch) :turn turn
                                       :tool-name tool)))
            (when-let [t (:thesis result)]
              (runs/set-thesis! conn run-id (:id branch) t))

            (let [branch (settle-predictions! conn branch turn [tool] before branch)]
              (if (:done? result)
                (state/add-message branch "user" (truncate (:result result)))
                ;; The single boundary. At most one steer, chosen in priority.
                (let [coverage (when (state/safe-state-due?
                                      branch (gates/threshold :cull-threshold))
                                 (state/snapshot-covers?
                                  branch (prolog/snapshot (:prolog branch))))
                      decision (arbiter/decide
                                {:branch branch
                                 :max-turns max-turns
                                 ;; How wide the beam already is, so the
                                 ;; reproduction rung knows whether the run
                                 ;; can afford offspring.
                                 :branch-count (or (:branch-count ctx) 1)
                                 :done-block (:done-block result)
                                 :directive (or (:pending-directive branch)
                                                (:directive ctx))
                                 :safe-state-coverage coverage})
                      {ctx-block :block branch :branch}
                      (context-block conn run-id branch
                                     (get-in parsed [:args :claim])
                                     (get-in ctx [:config :run :share-artifacts?]))
                      body (str (truncate (:result result))
                                (when ctx-block (str "\n\n" ctx-block))
                                (when decision (str "\n\n---\n\n" (:message decision))))
                      ;; Recorded exactly once. The row id is what a later turn
                      ;; settles, so writing it twice would leave one firing
                      ;; permanently open and inflate the tally.
                      firing-id (when decision
                                  (journal/record-gate!
                                   conn run-id
                                   {:branch-id (:id branch) :turn turn
                                    :gate (:gate decision)
                                    :priority (:priority decision)
                                    :message (:message decision)
                                    :prediction (:prediction decision)
                                    :window (:window decision)}))]
                  (when decision
                    (log/debug "branch" (:id branch) "turn" turn
                               "gate" (:gate decision)
                               "passed over" (:passed-over decision)))
                  (cond-> (-> branch
                              (dissoc :pending-directive)
                              (state/add-message "user" body))
                    decision (update :gate-history (fnil conj [])
                                     {:gate (:gate decision) :turn turn})
                    decision (update :open-predictions (fnil conj [])
                                     {:id firing-id
                                      :gate (:gate decision)
                                      :prediction (:prediction decision)
                                      :window (:window decision)
                                      :turn turn})
                    ;; Consumed by the NEXT call-model and cleared there, so a
                    ;; steer forecloses prose on exactly the turn it steers and
                    ;; no later one.
                    decision (assoc :prefill (arbiter/prefill-for decision))
                    ;; Record which budget notices have been delivered, or the
                    ;; gate cannot tell "happened" from "happened and I already
                    ;; reacted" and re-fires every turn past the threshold.
                    (= :turn-budget (:gate decision))
                    (assoc :notified-fractions
                           (gates/crossed-fractions branch max-turns))))))))))))

;; --- the run ----------------------------------------------------------------

(defn run!
  "Run one branch to completion. Returns {:status :answer :branch :run-id}."
  [{:keys [conn config llm-adapter llm-config problem max-turns]}]
  (let [max-turns (or max-turns (get-in config [:run :max-turns]) 40)
        run-id (runs/start-run! conn {:problem problem
                                      :provider (:provider llm-config)
                                      :model (:model llm-config)
                                      :max-turns max-turns
                                      :beam-width 1
                                      :prompt-digest (prompt-digest)})
        session (prolog/create-session (get-in config [:engines :swipl]))
        branch (state/new-branch {:id "B1" :problem problem :prolog session
                                  :messages (initial-messages problem)})
        ctx {:conn conn :run-id run-id :config config
             :llm-adapter llm-adapter :llm-config llm-config
             :max-turns max-turns}]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (try
      (loop [b branch, turn 1]
        (cond
          (not (state/active? b))
          (let [status (if (:final-answer b) :completed :abandoned)]
            (runs/close-branch! conn run-id "B1" (:status b) (:inactive-reason b))
            (runs/finish-run! conn run-id status (:final-answer b))
            {:status status :answer (:final-answer b) :branch b :run-id run-id})

          (> turn max-turns)
          (let [residual (state/residual b)]
            (runs/close-branch! conn run-id "B1" :exhausted
                                (str "turn cap of " max-turns " reached"))
            (journal/note! conn run-id :residual {:branch-id "B1" :data residual})
            (runs/finish-run! conn run-id :failed nil)
            {:status :exhausted :branch b :run-id run-id :residual residual})

          :else
          (recur (run-turn ctx b turn) (inc turn))))
      (finally
        (prolog/dispose! session)))))
