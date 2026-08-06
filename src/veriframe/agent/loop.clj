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
  Each shared artifact that enters a context is journaled, so whether sharing
  earns the beam its width is a question the journal can answer."
  [conn run-id branch last-claim share?]
  (let [others #(remove (fn [e] (= (:branch_id e) (:id branch))) %)
        fhits (others (if (str/blank? last-claim)
                        (failures/recent conn run-id 5)
                        (failures/similar conn run-id last-claim 5)))
        ahits (when share?
                (others (if (str/blank? last-claim)
                          (artifacts/recent conn run-id 5)
                          (artifacts/similar conn run-id last-claim 5))))]
    (doseq [a ahits]
      (journal/note! conn run-id :shared-artifact-hit
                     {:branch-id (:id branch)
                      :data {:claim (:claim a) :source-branch (:branch_id a)}}))
    (let [blocks (keep identity [(failures/render fhits)
                                 (artifacts/render ahits)])]
      (when (seq blocks) (str/join "\n\n" blocks)))))

;; --- one turn ---------------------------------------------------------------

(defn- call-model [ctx branch]
  (try
    (let [r (llm/chat (:llm-adapter ctx) (:llm-config ctx) (:messages branch))]
      {:ok true :response r})
    (catch Throwable e
      {:ok false :error (ex-message e)})))

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
            parsed (fence/parse-tool-call content)
            signals (fence/signals response parsed)
            branch (-> branch
                       (state/add-message "assistant" content)
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
            (journal/record-turn! conn run-id
                                  {:branch-id (:id branch) :turn turn
                                   :tool-name (or (:name parsed) "__no_call__")
                                   :result msg :category "failure"
                                   :parse-error (:parse-error parsed)
                                   :auto-repaired (:auto-repaired? parsed)
                                   :assistant-text content
                                   :reasoning-text (:reasoning response)})
            (-> branch
                (state/record-outcome {:category :failure :progress? false})
                (state/add-message "user" msg)))

          ;; A real tool call.
          (let [tool (:name parsed)
                result (tools/run-tool (assoc ctx :branch branch :turn turn
                                              :tool-name tool :args (:args parsed)))
                branch (-> (:branch result)
                           (state/record-outcome (assoc result :claim (get-in parsed [:args :claim])))
                           (state/add-turn {:turn turn :tool tool
                                            :category (:category result)}))
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
                                   :assistant-text content
                                   :reasoning-text (:reasoning response)})
            (when-let [a (:artifact result)]
              (journal/record-artifact! conn run-id
                                        (assoc a :branch-id (:id branch) :turn turn))
              ;; Only engine-confirmed artifacts enter the shared pool — the
              ;; entry condition that separates this from UCLA's self-reported
              ;; results. The flag is the diversity trade-off's off switch.
              (when (and (get-in ctx [:config :run :share-artifacts?])
                         (= :confirmed (:claim-status a)))
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
                                 :done-block (:done-block result)
                                 :directive (or (:pending-directive branch)
                                                (:directive ctx))
                                 :safe-state-coverage coverage})
                      ctx-block (context-block conn run-id branch
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
