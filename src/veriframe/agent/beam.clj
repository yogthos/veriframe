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
            [veriframe.agent.gates :as gates]
            [veriframe.agent.loop :as branch-loop]
            [veriframe.agent.state :as state]
            [veriframe.engine.lean-repl :as lean-repl]
            [veriframe.engine.prolog :as prolog]
            [veriframe.store.interventions :as interventions]
            [veriframe.store.journal :as journal]
            [veriframe.store.runs :as runs])
  (:refer-clojure :exclude [run!]))

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
                    "\n\nOther branches are pursuing the alternatives, so commit to"
                    " this one rather than hedging. Issue your first tool call."))))
      b)))

(defn- cull-or-keep
  "Apply the cull rule to a branch that just failed.

  A branch holding a recent confirmation is never culled — incremental
  strategies naturally look like verify size N, fail at N+1, verify N+1, and
  culling them throws away the most productive branch in the beam. The
  emergency-review gate is what talks to it instead, and the arbiter has
  already had its say by the time this runs.

  `survivors` is how many other branches would still be running. Culling exists
  to reallocate the beam's budget to branches doing better; when there is
  nobody to reallocate to, culling is just an early exit with turns left on the
  clock. The width sweep found this the direct way: the width-1 arm was culled
  at turn 9 of 12 and the run ended there, which reads as evidence against
  narrow beams and is actually a rule applied outside the situation it was
  written for. The last branch standing is never culled; the stuck and
  emergency-review gates keep talking to it instead."
  [branch survivors]
  (if (and (>= (:consecutive-failures branch) (gates/threshold :cull-threshold))
           (not (state/confirmed-in-last branch (gates/threshold :cull-recent-window)))
           (pos? survivors))
    (assoc branch
           :status :culled
           :inactive-reason (str "culled after " (:consecutive-failures branch)
                                 " consecutive failures with no recent confirmed work"))
    branch))

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
    (try (lean-repl/dispose! (:lean b)) (catch Throwable _ nil))))

(defn run!
  "Run a beam to completion.

  Returns {:status :answer :run-id :branches :residuals}. The first branch to
  land a `done` wins and the rest are abandoned, since paying for four more
  provider calls after the answer exists is pure waste."
  [{:keys [conn config llm-adapter llm-config problem max-turns beam-width
           abort on-start] :as opts}]
  (let [max-turns (or max-turns (get-in config [:run :max-turns]) 40)
        width (or beam-width (get-in config [:run :beam-width]) 5)
        run-id (runs/start-run! conn {:problem problem
                                      :provider (:provider llm-config)
                                      :model (:model llm-config)
                                      :max-turns max-turns
                                      :beam-width width
                                      :prompt-digest (branch-loop/prompt-digest)})
        ;; Every session ever opened, including forked children, so the
        ;; supervisor can tear them all down regardless of how the run ended.
        ;; The stop path must not depend on the agent's state — the RAX
        ;; manager could always halt the Lisp task no matter what it believed.
        sessions (atom [])
        live-branches (atom [])
        ctx {:conn conn :run-id run-id :config config :problem problem
             :llm-adapter llm-adapter :llm-config llm-config
             :max-turns max-turns :beam? (> width 1) :sessions sessions}
        initial (mapv #(open-branch! ctx (str "B" (inc %)) nil nil 0) (range width))]
    ;; Hand the id back before the first turn so a caller that started this in
    ;; the background can address the run while it is still running.
    (when on-start (on-start run-id))
    (try
      (loop [branches initial, turn 1]
        ;; Kept current so the finally block can tear down Lean sessions,
        ;; which tools open lazily on a branch rather than the scheduler
        ;; opening them up front.
        (reset! live-branches branches)
        (let [active (filterv state/active? branches)
              done-branch (first (filter :final-answer branches))]
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
                                      (str "superseded by " (:id done-branch) " done()")))
                (runs/finish-run! conn run-id :completed (:final-answer done-branch))
                {:status :completed :answer (:final-answer done-branch)
                 :run-id run-id :branches branches})

            (or (empty? active) (> turn max-turns))
            (let [residuals (keep state/residual branches)]
              (doseq [b active]
                (runs/close-branch! conn run-id (:id b) :exhausted
                                    (str "turn cap of " max-turns " reached")))
              (doseq [r residuals]
                (journal/note! conn run-id :residual {:branch-id (:branch r) :data r}))
              (runs/finish-run! conn run-id :failed nil)
              {:status (if (empty? active) :exhausted :exhausted)
               :run-id run-id :branches branches :residuals (vec residuals)})

            :else
            (let [directives (interventions/pending conn run-id)
                  active (drain-directives! ctx active directives turn)
                  advanced (advance-all ctx (filterv state/active? active) turn)
                  ;; Cull before forking, so a branch culled this turn does not
                  ;; also get to spend the branch budget on children.
                  ;; A branch is only culled if someone else would still be
                  ;; running. Evaluated left to right against the count of
                  ;; branches that survive the decision so far.
                  culled (first
                          (reduce (fn [[acc alive] b]
                                    (let [b' (cull-or-keep b (dec alive))]
                                      [(conj acc b')
                                       (if (state/active? b') alive (dec alive))]))
                                  [[] (count advanced)]
                                  advanced))
                  _ (doseq [b culled
                            :when (and (not (state/active? b))
                                       (not (:final-answer b)))]
                      (runs/close-branch! conn run-id (:id b) (:status b)
                                          (:inactive-reason b)))
                  inactive (filterv (complement state/active?) branches)
                  all-now (into (vec inactive) culled)
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

(defn summary
  "One line per branch, for logs and the run response."
  [{:keys [branches residuals]}]
  (str/join "\n" (concat (map state/describe branches)
                         (keep state/render-residual residuals))))
