;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.beam
  "The beam: many branches advancing the same problem in parallel.

  Each branch carries its own Prolog session, message history and turn log.
  The only thing they share is the failure log, and that sharing is the point:
  an approach one branch disproved should not be retried by another. It is
  FTS-ranked rather than broadcast whole, so a branch is shown the failures
  most like what it just tried instead of everything everyone ever got wrong.

  Scheduling is a barrier per turn: every active branch advances once, then
  culls, forks and the done check run against the settled set. A pipeline would
  be faster in wall clock, but a branch deciding whether to fork needs the
  failure log as of the whole beam's last turn, not as of whenever it happened
  to finish.

  The width is not treated as justified. The original never measured five
  branches against one branch at five times the turn budget, and
  `veriframe.bench.beam` is the comparison."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [veriframe.agent.claims :as claims]
            [veriframe.agent.critic :as critic]
            [veriframe.agent.gates :as gates]
            [veriframe.agent.loop :as branch-loop]
            [veriframe.agent.state :as state]
            [veriframe.engine.lean-repl :as lean-repl]
            [veriframe.engine.octave :as octave]
            [veriframe.engine.prolog :as prolog]
            [veriframe.store.artifacts :as artifacts]
            [veriframe.store.failures :as failures]
            [veriframe.store.interventions :as interventions]
            [veriframe.store.journal :as journal]
            [veriframe.store.runs :as runs])
  (:refer-clojure :exclude [run!]))

(defn- crossover-block
  "What OTHER lineages have proved, for a newly forked child's opening
  context.

  This is the recombination half. A child that inherits only its parent's
  history is mutation: it deepens one line. Opening it holding the results
  its aunts and uncles confirmed lets a fork COMBINE lineages — the branch
  that proved a bound in Prolog and the branch that proved a structure in
  Lean can have a child that uses both. Own-lineage results are excluded
  because the child already carries them in its inherited history."
  [conn run-id parent-id]
  (let [others (remove #(= parent-id (:branch_id %))
                       (journal/artifacts conn run-id))
        confirmed (filter #(= "confirmed" (:claim_status %)) others)]
    (when (seq confirmed)
      (str "\n\n**Confirmed by other lineages in this run** — engine-verified,"
           " and yours to build on or combine with:\n"
           (str/join "\n"
                     (for [a (take-last 8 confirmed)]
                       (str "- [" (:branch_id a) " " (:kind a) "] " (:claim a))))))))

(defn- open-branch!
  [{:keys [conn run-id config problem sessions]} id parent-id thesis turn]
  (let [session (prolog/create-session (get-in config [:engines :swipl]))
        _ (when sessions (swap! sessions conj session))
        b (state/new-branch
           {:id id :parent-id parent-id :problem problem :prolog session
            :created-at-turn turn
            :messages (branch-loop/initial-messages problem)})]
    (runs/open-branch! conn run-id {:branch-id id :parent-id parent-id
                                    :created-at-turn turn})
    (if thesis
      (do (runs/set-thesis! conn run-id id thesis)
          (-> b
              (assoc :thesis thesis)
              (state/add-message
               "user"
               (str "You were forked from " parent-id " to pursue one specific"
                    " approach:\n\n**" (:goal thesis) "**"
                    (when (:technique thesis) (str "\nTechnique: " (:technique thesis)))
                    (crossover-block conn run-id parent-id)
                    "\n\nOther branches are pursuing the alternatives, so commit to"
                    " this one rather than hedging. Issue your first tool call."))))
      b)))

(defn- cull-or-keep
  "Apply the retention rule to a branch that just failed.

  A branch holding a recent confirmation is never culled — incremental
  strategies naturally look like verify size N, fail at N+1, verify N+1, and
  culling them throws away the most productive branch in the beam. The
  emergency-review gate is what talks to it instead, and the arbiter has
  already had its say by the time this runs.

  The scalar rule (consecutive failures, no recent confirmation) is the
  TRIGGER; the critic's Pareto frontier is the verdict. A triggered branch
  is culled when a living sibling dominates it on every critic objective,
  when the critic itself scored the line a dead end, or when no scores
  exist (the critic is advisory — absent, the scalar rule stands). A
  triggered branch that is NOT dominated keeps its distinct strengths and
  survives, journaled and on a clock: at cull-hard-multiple times the
  threshold the reprieve ends unconditionally, because Pareto's known
  weakness is permissiveness and a zombie beam is the failure mode.

  `survivors` is how many other branches would still be running. Culling
  exists to reallocate the beam's budget to branches doing better; when
  there is nobody to reallocate to, culling is just an early exit with turns
  left on the clock. The width sweep found this the direct way: the width-1
  arm was culled at turn 9 of 12 and the run ended there. The last branch
  standing is never culled; the stuck and emergency-review gates keep
  talking to it instead."
  [{:keys [conn run-id]} branch survivors sibling-scores]
  (let [threshold (gates/threshold :cull-threshold)
        fails (or (:consecutive-failures branch) 0)
        cull (fn [why] (assoc branch :status :culled :inactive-reason why))
        scores (get-in branch [:critic :scores])
        hard-floor (* (gates/threshold :cull-hard-multiple) threshold)]
    (cond
      (not (and (>= fails threshold)
                (not (state/confirmed-in-last branch
                                              (gates/threshold :cull-recent-window)))
                (pos? survivors)))
      branch

      (>= fails hard-floor)
      (cull (str "culled after " fails
                 " consecutive failures; the Pareto reprieve was spent"))

      ;; A dead end is a dead end at any age; the critic's own verdict is
      ;; the one judgement that does not depend on how long the branch has
      ;; had to accumulate anything.
      (and scores (<= (:viability scores) 1))
      (cull (str "culled after " fails
                 " consecutive failures; the critic scored the line a dead end"))

      ;; Juvenile grace. Progress and momentum are age-correlated, so a
      ;; newborn is dominated by its own parent one turn after being forked.
      ;; Let it express itself first.
      (< (state/turn-count branch) (gates/threshold :juvenile-grace))
      (do (when (and conn run-id)
            (journal/note! conn run-id :cull-spared
                           {:branch-id (:id branch)
                            :data {:scores scores :failures fails :juvenile? true}}))
          (state/add-message
           branch "user"
           (str "[harness] " fails " consecutive verifications have failed."
                " A branch this new is not culled for it — you were forked to"
                " explore a distinct line and have not had the turns to show"
                " what it is worth yet. Change something concrete and keep"
                " going.")))

      (nil? scores)
      (cull (str "culled after " fails
                 " consecutive failures with no recent confirmed work"))

      (critic/dominated? scores sibling-scores)
      (cull (str "culled after " fails
                 " consecutive failures; dominated by a sibling on every"
                 " critic objective"))

      :else
      (do (when (and conn run-id)
            (journal/note! conn run-id :cull-spared
                           {:branch-id (:id branch)
                            :data {:scores scores :failures fails}}))
          (state/add-message
           branch "user"
           (str "[harness] " fails " consecutive verifications have failed,"
                " which normally culls a branch, but no sibling dominates"
                " your line on the critic's objectives — it survives on its"
                " distinct strengths. Change something concrete about the"
                " approach; the reprieve ends unconditionally at " hard-floor
                " consecutive failures."))))))

(defn- ensure-scored
  "Fresh critic scores for every active branch, at most one sub-LLM call per
  branch per :critic-every window. A scoring that fails leaves the previous
  scores in place — stale information beats invented information."
  [ctx branches turn]
  (mapv (fn [b]
          (if (and (state/active? b)
                   (or (nil? (get-in b [:critic :turn]))
                       (>= (- turn (get-in b [:critic :turn]))
                           (gates/threshold :critic-every))))
            (let [siblings (filterv #(and (state/active? %)
                                          (not= (:id %) (:id b)))
                                    branches)]
              (if-let [s (critic/score! ctx b siblings turn)]
                (assoc b :critic s)
                b))
            b))
        branches))

(defn- invite-fork
  "When the beam has room under the total cap and a branch's critic scores
  clear the fork floor, invite the strongest such branch to fork.

  The frontier grows on evidence rather than starting wide: a run may open
  at width 1 or 2 and widen only where verified progress is happening. The
  invitation is a plain harness message — the model decides whether it has
  a genuinely distinct sub-approach, and branch_theses does the spawning
  under the existing total cap. No prediction is recorded; declining is a
  legitimate answer and not a settled miss."
  [{:keys [conn run-id]} branches total-count turn]
  (let [cap (gates/threshold :max-total-branches)
        floor (gates/threshold :fork-invite-floor)
        cooldown (gates/threshold :fork-invite-cooldown)
        candidate (when (< total-count cap)
                    (->> branches
                         (filter state/active?)
                         (filter #(when-let [s (get-in % [:critic :scores])]
                                    (and (>= (:viability s) (:viability floor))
                                         (>= (:progress s) (:progress floor)))))
                         (remove #(when-let [t (:fork-invited %)]
                                    (< (- turn t) cooldown)))
                         (sort-by #(- (reduce + (vals (get-in % [:critic :scores])))))
                         first))]
    (if-not candidate
      branches
      (do (when (and conn run-id)
            (journal/note! conn run-id :fork-invite
                           {:branch-id (:id candidate) :turn turn
                            :data (get-in candidate [:critic :scores])}))
          (mapv #(if (= (:id %) (:id candidate))
                   (-> %
                       (assoc :fork-invited turn)
                       (state/add-message
                        "user"
                        (str "[harness] Your line is showing verified progress"
                             " and the beam has room. If you can name a"
                             " genuinely distinct sub-approach worth exploring"
                             " in parallel, call branch_theses — your current"
                             " thesis first, the alternatives after. If you"
                             " cannot, continue as you were; this is an"
                             " invitation, not an instruction.")))
                   %)
                branches)))))

(defn- spawn-children!
  "Turn a branch's pending theses into sibling branches, under the total cap.

  Returns [children updated-parent]. The cap is a cost ceiling: every branch is
  another concurrent provider call per turn plus another engine process, so
  when it binds the parent is told rather than the request silently shrinking."
  [ctx parent existing-count turn]
  (let [pending (:pending-branch-theses parent)
        cap (gates/threshold :max-total-branches)
        room (max 0 (- cap existing-count))
        take-n (min room (count pending))
        spawning (vec (take take-n pending))
        parent (assoc parent :pending-branch-theses nil)]
    (cond
      (empty? pending) [[] parent]

      (zero? room)
      [[] (state/add-message
           parent "user"
           (str "[harness] Your branch_theses call asked for " (count pending)
                " sibling branch(es), but the run is at the cap of " cap
                " branches. None were spawned; assume the existing branches"
                " already cover similar ground."))]

      :else
      (let [children (map-indexed
                      (fn [i t]
                        (open-branch! ctx (str (:id parent) "." (+ i 2))
                                      (:id parent) t turn))
                      spawning)
            parent (cond-> parent
                     (< take-n (count pending))
                     (state/add-message
                      "user"
                      (str "[harness] You asked for " (count pending)
                           " sibling branch(es); the cap of " cap
                           " allowed " take-n ". The rest were dropped.")))]
        [(vec children) parent]))))

(def default-turn-deadline-ms
  "A hard ceiling on one branch turn, independent of the HTTP layer's own
  timeout.

  Found by the first live beam: B1's provider call hung, and because the
  scheduler is a barrier the whole beam sat still behind it for fifteen
  minutes. The TypeScript harness carries the same guard and its comment says
  why — the socket timeout does not always fire, the connection sits
  ESTABLISHED with zero throughput, and the caller waits forever.

  This is the RAX-manager principle applied to a branch: the stop path must not
  depend on the component's cooperation. A branch that blows the deadline
  forfeits the turn and the beam moves on.

  Sized to the worst LEGITIMATE turn rather than to the typical one, because
  forfeiting a turn that was about to succeed is expensive twice over: the
  branch loses the turn and the failure is logged for other branches to avoid.
  The worst legitimate turn is a provider call at its 300000ms socket timeout
  followed by a Lean tactic at its own 300000ms, so the old 420000ms could not
  fit one without a false kill. That is separate from what made the first Lean
  turn hopeless — a 377927ms Mathlib import inside a 420000ms budget — which is
  fixed by warming at startup rather than by this number."
  (or (some-> (System/getenv "HARNESS_TURN_DEADLINE_MS") parse-long)
      900000))

(defn- drain-directives!
  "Apply pending human directives at the boundary, and record what happened to
  each.

  A directive is never silently dropped. `cull` on the last running branch is
  refused with a reason rather than obeyed, because obeying it would end the
  run in a way the person almost certainly did not intend, and rather than
  guessing we say so."
  [{:keys [conn run-id]} branches directives turn]
  (reduce
   (fn [bs d]
     (let [kind (:kind d)
           target (:branch_id d)
           matches? (fn [b] (or (nil? target) (= target (:id b))))
           alive (count (filter state/active? bs))]
       (case kind
         "cull"
         (if (<= alive 1)
           (do (interventions/resolve! conn run-id (:id d) :rejected
                                       "refused: it is the last branch running" turn)
               bs)
           (do (interventions/resolve! conn run-id (:id d) :applied nil turn)
               (mapv #(if (and (matches? %) (state/active? %))
                        (assoc % :status :culled :inactive-reason "culled by a human")
                        %)
                     bs)))

         ("message" "review")
         (do (interventions/resolve! conn run-id (:id d) :applied nil turn)
             ;; Delivered as a directive on the branch, which the arbiter puts
             ;; at priority zero — above every machine gate.
             (mapv #(if (matches? %) (assoc % :pending-directive d) %) bs))

         ;; pause / resume / extend / fork are recognized but not yet wired to
         ;; a scheduler action. Rejecting explicitly beats accepting silently.
         (do (interventions/resolve! conn run-id (:id d) :rejected
                                     (str kind " is not wired to a scheduler action yet")
                                     turn)
             bs))))
   branches
   directives))

(defn- advance-all
  "One turn for every active branch, concurrently, each under a hard deadline.

  A branch that throws is abandoned rather than taking the beam down with it:
  one failing engine session must not cost the other four their work. A branch
  that hangs loses only its own turn. Phase 1 proved five concurrent swipl
  sessions hold, which is what makes this real parallelism rather than a loop
  wearing futures."
  [ctx branches turn]
  (let [deadline (or (:turn-deadline-ms ctx) default-turn-deadline-ms)
        pending (mapv (fn [b]
                        [b (future
                             (try
                               (branch-loop/run-turn ctx b turn)
                               (catch Throwable e
                                 (log/warn "branch" (:id b) "died on turn" turn
                                           ":" (ex-message e))
                                 (assoc b :status :abandoned
                                        :inactive-reason
                                        (str "branch error: " (ex-message e))))))])
                      branches)]
    (mapv (fn [[b fut]]
            (let [r (deref fut deadline ::timeout)]
              (if (= ::timeout r)
                (do (log/warn "branch" (:id b) "exceeded the turn deadline on turn" turn)
                    ;; Not a verification failure: the branch did not get an
                    ;; answer to be wrong about. It loses the turn and is told
                    ;; so, and the dangling call is left to finish or not.
                    (-> b
                        (state/add-message
                         "user"
                         (str "[harness] Your previous turn exceeded the "
                              (quot deadline 1000) "s deadline and was abandoned."
                              " Keep your next response short and call a tool."))
                        (update :timeouts (fnil inc 0))))
                r)))
          pending)))

(defn- dispose-lean! [branches]
  (doseq [b branches :when (:lean b)]
    (try (lean-repl/dispose! (:lean b)) (catch Throwable _ nil)))
  ;; Octave leaves a workspace directory rather than a process, so this is disk
  ;; rather than memory, but it still wants clearing.
  (doseq [b branches :when (:octave b)]
    (try (octave/dispose! (:octave b)) (catch Throwable _ nil))))

(defn- dispose-branch-engines!
  "Release one branch's engine sessions. Safe to call twice: dispose! on an
  already-dead session is a no-op, and the run-end teardown still sweeps
  everything in case a branch never reached this path."
  [b]
  (when (:lean b) (try (lean-repl/dispose! (:lean b)) (catch Throwable _ nil)))
  (when (:octave b) (try (octave/dispose! (:octave b)) (catch Throwable _ nil)))
  (when (:prolog b) (try (prolog/dispose! (:prolog b)) (catch Throwable _ nil))))

(defn- finish-now?
  "Should a shipped branch end the run? Returns the winning branch, or nil to
  keep exploring.

  Winner-takes-all is right for a question with one answer and wrong for a
  research campaign: the first branch to clear the bar terminates every other
  line, so the run returns the cheapest qualifying result rather than the best
  one. With `:stop-on-first-done?` false a shipped branch goes inactive
  holding its answer and the rest keep working; the run ends when nobody is
  left to explore, and select-done-branch ranks every finished branch on the
  evidence it carries."
  [ctx done-branch branches]
  (when done-branch
    (if (get-in ctx [:config :run :stop-on-first-done?] true)
      done-branch
      (when-not (some #(and (state/active? %) (not (:final-answer %))) branches)
        done-branch))))

(defn select-done-branch
  "The winner among branches that landed :final-answer this round.

  The choice is mechanical — state/rank-finished over the engine-audited
  evidence each branch carries — so no model sits in the path where UCLA
  needed an LLM selector. When more than one branch is eligible the choice
  is journalled with each candidate's id and ranking key plus the winner,
  so it is auditable from the run record. A single candidate is today's
  behavior and journals nothing."
  [{:keys [conn run-id]} candidates]
  (let [winner (first (state/rank-finished candidates))]
    (when (and conn run-id (< 1 (count candidates)))
      (journal/note! conn run-id :candidate-selection
                     {:data {:candidates (mapv (fn [b]
                                                 {:branch-id (:id b)
                                                  :key (state/finished-key b)})
                                               candidates)
                             :winner (:id winner)}}))
    winner))

(defn run-rounds
  "The beam's scheduling loop over `branches`, starting at round `start-turn`.

  Split out of `run!` so a resumed run can enter the same loop at the round
  after its journal's last recorded turn. `max-turns` is read from the ctx,
  which a resume builds from the runs row — the ORIGINAL budget — so starting
  at turn N+1 of M is what keeps a crash from re-granting the N turns before
  it. This is UCLA's persisted run-start anchor: the anchor is the turns
  table, and the budget is the runs row.

  Returns {:status :completed|:aborted|:exhausted :run-id :branches ...}.
  Teardown lives here rather than in the callers, so every engine session is
  disposed no matter how the run ended."
  [{:keys [conn run-id max-turns abort sessions] :as ctx} branches start-turn]
  (let [live-branches (atom branches)]
    (try
      (loop [branches branches, turn start-turn]
        ;; Kept current so the finally block can tear down Lean sessions,
        ;; which tools open lazily on a branch rather than the scheduler
        ;; opening them up front.
        (reset! live-branches branches)
        (let [active (filterv state/active? branches)
              done-candidates (filterv :final-answer branches)
              multi-candidate? (< 1 (count done-candidates))
              ;; A shipped branch always stops working; whether it stops the
              ;; RUN is the campaign question finish-now? answers.
              done-branch (finish-now? ctx (select-done-branch ctx done-candidates)
                                       branches)]
          (cond
            ;; Checked at the top of every round. An abort must not need the
            ;; run's cooperation, so it is a flag the scheduler reads rather
            ;; than a message a branch has to receive.
            (and abort @abort)
            (do (doseq [b active]
                  (runs/close-branch! conn run-id (:id b) :abandoned "aborted"))
                {:status :aborted :run-id run-id :branches branches})
            done-branch
            (do (doseq [b branches
                        :when (and (state/active? b) (not= (:id b) (:id done-branch)))]
                  (runs/close-branch! conn run-id (:id b) :abandoned
                                      (str (if multi-candidate?
                                             "outranked by "
                                             "superseded by ")
                                           (:id done-branch)
                                           (when-not multi-candidate?
                                             " done()"))))
                (runs/finish-run! conn run-id :completed (:final-answer done-branch))
                {:status :completed :answer (:final-answer done-branch)
                 :run-id run-id :branches branches})

            (or (empty? active) (> turn max-turns))
            (let [residuals (keep state/residual branches)
                  report (state/build-residual-report
                          {:branches branches
                           :failures (failures/recent conn run-id 10)
                           :gate-tally (journal/gate-tally conn run-id)
                           :max-turns max-turns})]
              (doseq [b active]
                (runs/close-branch! conn run-id (:id b) :exhausted
                                    (str "turn cap of " max-turns " reached")))
              (doseq [r residuals]
                (journal/note! conn run-id :residual {:branch-id (:branch r) :data r}))
              (journal/note! conn run-id :residual-report {:data report})
              (runs/finish-run! conn run-id :failed nil)
              {:status :exhausted
               :run-id run-id :branches branches :residuals (vec residuals)
               :report report
               :report-text (state/render-residual-report report)})

            :else
            (let [directives (interventions/pending conn run-id)
                  active (drain-directives! ctx active directives turn)
                  advanced (advance-all
                            (assoc ctx :branch-count (count branches))
                            (filterv state/active? active) turn)
                  ;; Critic scores refresh on post-turn state, before any
                  ;; retention decision reads them.
                  advanced (ensure-scored ctx advanced turn)
                  ;; Cull before forking, so a branch culled this turn does not
                  ;; also get to spend the branch budget on children.
                  ;; A branch is only culled if someone else would still be
                  ;; running. Evaluated left to right against the count of
                  ;; branches that survive the decision so far.
                  culled (first
                          (reduce (fn [[acc alive] b]
                                    (let [sibs (keep #(when (and (state/active? %)
                                                                 (not= (:id %) (:id b)))
                                                        (get-in % [:critic :scores]))
                                                     advanced)
                                          b' (cull-or-keep ctx b (dec alive) sibs)]
                                      [(conj acc b')
                                       (if (state/active? b') alive (dec alive))]))
                                  [[] (count advanced)]
                                  advanced))
                  _ (doseq [b culled
                            :when (and (not (state/active? b))
                                       (not (:final-answer b)))]
                      (runs/close-branch! conn run-id (:id b) (:status b)
                                          (:inactive-reason b))
                      ;; Release the engines as the branch goes inactive rather
                      ;; than at run end. A Lean session holds ~0.83GB, so a
                      ;; branch culled at turn 10 of 80 was sitting on that for
                      ;; the other 70 turns. The run-end `finally` still covers
                      ;; everything, including the branches still alive; this
                      ;; only stops a dead branch holding memory it can no
                      ;; longer use.
                      (dispose-branch-engines! b))
                  inactive (filterv (complement state/active?) branches)
                  all-now (into (vec inactive) culled)
                  ;; Grow the frontier where the evidence is: after the cull,
                  ;; so a freed slot can be refilled the same round.
                  culled (invite-fork ctx culled (count all-now) turn)
                  [children updated]
                  (reduce (fn [[acc bs] b]
                            (if (and (state/active? b) (seq (:pending-branch-theses b)))
                              (let [[kids parent] (spawn-children!
                                                   ctx b (+ (count all-now) (count acc)) turn)]
                                [(into acc kids) (conj bs parent)])
                              [acc (conj bs b)]))
                          [[] []]
                          culled)]
              (recur (into (into (vec inactive) updated) children) (inc turn))))))
      (finally
        ;; Prolog sessions are opened by the scheduler; Lean sessions are
        ;; opened lazily by a tool on whichever branch first needs one, so
        ;; they are collected off the branches at the end.
        (doseq [s @sessions]
          (try (prolog/dispose! s) (catch Throwable _ nil)))
        (dispose-lean! @live-branches)))))

(defn run!
  "Run a beam to completion.

  Returns {:status :answer :run-id :branches :residuals}. The first branch to
  land a `done` wins and the rest are abandoned, since paying for four more
  provider calls after the answer exists is pure waste."
  [{:keys [conn config llm-adapter llm-config problem max-turns beam-width
           abort on-start seed-run] :as opts}]
  (let [max-turns (or max-turns (get-in config [:run :max-turns]) 40)
        width (or beam-width (get-in config [:run :beam-width]) 5)
        ;; Seeding forces sharing on for this run regardless of the config
        ;; flag: seeds enter through the shared log's context blocks, and
        ;; seeds nobody reads would be dead rows.
        config (cond-> config
                 seed-run (assoc-in [:run :share-artifacts?] true))
        run-id (runs/start-run! conn {:problem problem
                                      :provider (:provider llm-config)
                                      :model (:model llm-config)
                                      :max-turns max-turns
                                      :beam-width width
                                      :prompt-digest (branch-loop/prompt-digest)})
        ;; Seeded before any branch opens, so the first context block a
        ;; branch ever sees can already carry inherited lemmas.
        _ (when seed-run (artifacts/seed-from-run! conn run-id seed-run))
        ;; Every session ever opened, including forked children, so the
        ;; supervisor can tear them all down regardless of how the run ended.
        ;; The stop path must not depend on the agent's state — the RAX
        ;; manager could always halt the Lisp task no matter what it believed.
        sessions (atom [])
        ctx {:conn conn :run-id run-id :config config :problem problem
             :llm-adapter llm-adapter :llm-config llm-config
             :max-turns max-turns :beam? (> width 1) :sessions sessions
             :abort abort
             ;; One claim registry per run: two branches reaching the same
             ;; claim share one slow verification instead of racing it.
             :claims (claims/new-registry)}
        initial (mapv #(open-branch! ctx (str "B" (inc %)) nil nil 0) (range width))]
    ;; Hand the id back before the first turn so a caller that started this in
    ;; the background can address the run while it is still running.
    (when on-start (on-start run-id))
    (run-rounds ctx initial 1)))

(defn summary
  "One line per branch, for logs and the run response."
  [{:keys [branches residuals]}]
  (str/join "\n" (concat (map state/describe branches)
                         (keep state/render-residual residuals))))
