;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.gates
  "Gate definitions: the conditions under which the harness says something to
  the model, and what it expects to happen next.

  A gate is data. It has a precondition re-evaluated every tick, a message, a
  budget, and a prediction that a later turn settles deterministically. The
  arbiter picks at most one per boundary; nothing here decides to fire.

  Preconditions are re-evaluated rather than latched by one-shot counters,
  which is the behavior-tree property worth taking from Kelley (arXiv
  2404.07439): a condition that stopped holding should stop firing, and a
  counter cannot express that.

  Every gate declares a prediction because a gate that cannot say what should
  change is one whose effect nobody can check. Settling them is what makes the
  gate tally worth reading (AHE decision observability)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [veriframe.agent.state :as state]))

(defn load-config
  "Gate thresholds from resources/gates.edn. Read through io/resource so the
  path works interpreted and inside an AOT binary."
  []
  (edn/read-string (slurp (io/resource "gates.edn"))))

(defonce ^:private config-cache (atom nil))

(defn config []
  (or @config-cache (reset! config-cache (load-config))))

(defn reload-config! [] (reset! config-cache (load-config)))

(defn threshold [k]
  (get-in (config) [k :value]))

(defn- prompt [name]
  (slurp (io/resource (str "prompts/" name ".md"))))

(defn- fired-count [branch gate]
  (count (filter #(= gate (:gate %)) (:gate-history branch))))

;; --- gate definitions -------------------------------------------------------
;;
;; Priority is ascending: 0 is highest. The order encodes what matters when two
;; conditions hold at once, and it is the whole content of the arbiter.

(def gates
  [{:gate :human-directive
    :priority 0
    :budget nil
    :doc "A human told the branch to do something. Outranks every machine gate,
          which is dirge PR 717's finding arriving as a design property rather
          than a bug fix."
    :when (fn [{:keys [directive]}] (some? directive))
    :message (fn [{:keys [directive]}]
               (str "**A human has intervened in this run.**\n\n"
                    (:payload directive)
                    "\n\nThis takes precedence over anything the harness has told"
                    " you. Act on it on this turn."))
    :prediction (fn [_] "the branch acts on the directive on the next turn")
    :window 1}

   {:gate :done-blocked
    :priority 1
    :budget :max-done-blocks
    :doc "done was called without the evidence it requires. The message says
          which gate is unmet, because 'not yet' with no reason produces
          another identical attempt."
    :when (fn [{:keys [done-block]}] (some? done-block))
    :message (fn [{:keys [done-block]}] done-block)
    :prediction (fn [_] "the branch supplies the missing evidence or gives up")
    :window 3}

   {:gate :safe-state
    :priority 2
    :budget :max-safe-state-aborts
    :doc "DS1's third failure rung. The branch has failed repeatedly since a
          confirmation, so the rules it added since are the suspect, not the
          claim. Advisory by default: it names the fallback rather than
          performing it, because a restore that is not fully covered produces a
          session that never existed."
    :when (fn [{:keys [branch]}]
            (state/safe-state-due? branch (threshold :cull-threshold)))
    :message (fn [{:keys [branch safe-state-coverage]}]
               (str (prompt "safe-state")
                    "\n\nYour last confirmed result was at turn "
                    (:green-at-turn branch) ". "
                    (if (:ok safe-state-coverage)
                      (str "Everything added since is named and retractable, so"
                           " the harness can rewind cleanly on request.")
                      (str "The harness CANNOT rewind for you: "
                           (:reason safe-state-coverage)
                           " Undo by hand with retract_rule, or start a new"
                           " named rule set."))))
    :prediction (fn [_] "the branch retracts, changes technique, or ships what it has")
    :window 3}

   {:gate :wind-down
    :priority 3
    :budget :max-wind-down-steers
    :doc "The branch has passed the fraction of the turn cap reserved for
          shipping. UCLA's harness reserves a deadline tail for assembly and
          shipping, and a run with no such tail spends its whole budget
          exploring and ships nothing. This steer outranks every nudge rung so
          a branch near its cap is told to ship before it is nagged to do more,
          but it never outranks done-blocked or safe-state: a branch that has
          just been told it cannot ship is not being asked to ship."
    :when (fn [{:keys [branch max-turns]}]
            (and (state/active? branch)
                 (>= (state/turn-count branch)
                     (* (threshold :wind-down-fraction) (max 1 max-turns)))))
    :message (fn [{:keys [branch max-turns]}]
               (str (prompt "wind-down")
                    "\n\nYou are at turn " (state/turn-count branch)
                    " of " max-turns "."))
    :prediction (fn [_] "the branch calls review, audit, or done")
    :window 3}

   {:gate :emergency-review
    :priority 4
    :budget :max-emergency-reviews
    :doc "At the cull threshold but holding a recent confirmation. Rather than
          culling the branch that produced the most, tell it to ship what it
          has or change approach. Guarded since a live run re-fired it on
          three consecutive boundaries: the precondition persists while the
          branch is busy complying, which is what re-fire guards are for."
    :when (fn [{:keys [branch]}]
            (and (>= (:consecutive-failures branch) (threshold :cull-threshold))
                 (state/confirmed-in-last branch (threshold :cull-recent-window))))
    :message (fn [_] (prompt "emergency-review"))
    :prediction (fn [_] "the branch calls review or done, or changes technique")
    :window 3}

   {:gate :milestone
    :priority 5
    :budget :max-milestone-nudges
    :doc "First confirmed artifact on this branch that engages what the branch
          is working on. Runs that do not ship at this moment usually fail:
          after a confirmation the instinct is to push for more, and that
          usually loses the verified result. Relevance-filtered because the
          gate congratulated a branch for verifying that between/3 works
          (vf-8fl), which is the same failure the zebra run produced."
    :when (fn [{:keys [branch]}] (state/has-relevant-confirmed? branch))
    :message (fn [_] (prompt "milestone"))
    :prediction (fn [_] "the branch calls review or done within two turns")
    :window 2}

   {:gate :branch-out
    :priority 7
    :budget :max-branch-outs
    :doc "A branch that just confirmed something, with room left in the beam.

          This is the reproduction half of the loop. The beam had selection
          (critic scores, Pareto retention) and no variation: `branch_theses`
          existed, the scheduler invited forks, and across every live run
          the count of forked children was zero — seven invitations went out
          in one run and every one was declined. An invitation the model may
          decline is not a mechanism; a gate with a settled prediction is.

          Fires on evidence rather than on hope: a confirmation is the
          fitness signal, so the branch worth reproducing from is the one
          that just proved something ABOUT THE PROBLEM — a fork costs another
          engine process and another model call per turn, and is not worth
          spending on a branch that confirmed its own tooling. Silent once the
          run is winding down, because a new line that late cannot finish."
    :when (fn [{:keys [branch branch-count max-turns]}]
            (let [last-fired (->> (:gate-history branch)
                                  (filter #(= :branch-out (:gate %)))
                                  (map :turn)
                                  (reduce max -1000))]
              (and (state/has-relevant-confirmed? branch)
                   (< (or branch-count 0) (threshold :max-total-branches))
                   ;; Not while the branch is still acting on the last ask.
                   (>= (- (state/turn-count branch) last-fired)
                       (threshold :branch-out-cooldown))
                   (< (state/turn-count branch)
                      (* (threshold :wind-down-fraction) (max 1 (or max-turns 40)))))))
    :message (fn [_] (prompt "branch-out"))
    :prediction (fn [_] "the branch calls branch_theses")
    ;; The one tool this prediction names, so the steer can be prefilled
    ;; rather than suggested. Only set where the prediction is
    ;; unambiguous: a gate predicting "review or done" must not have the
    ;; harness pick for the branch.
    :tool "branch_theses"
    :window 3}

   {:gate :repopulate
    :priority 6
    :budget :max-repopulates
    :doc "The beam has fallen below its target width and this branch is the
          strongest survivor. The scheduler marks the branch (it is the only
          thing that knows the alive count, the target, and who is strongest);
          this rung is what actually asks.

          It was an invitation appended straight from the scheduler until it
          became this. That carried no prediction, settled nothing, and showed
          up in no gate tally: gen-17 sent 12 in its first 120 turns and 9 were
          declined, which nobody could see without counting branch-opened
          events by hand. It was also a second harness voice on a boundary that
          had already had its one steer.

          Ranked just above branch-out. Both ask for offspring, but this one
          fires because the beam is dying rather than because a branch is
          thriving, and refilling an empty slot is the more urgent of the two."
    :when (fn [{:keys [branch]}]
            (when-let [due (:repopulate-due branch)]
              (let [last-fired (->> (:gate-history branch)
                                    (filter #(= :repopulate (:gate %)))
                                    (map :turn)
                                    (reduce max -1000))]
                ;; Only for a mark the branch has not already been asked about,
                ;; so a mark that survives on the branch does not re-fire every
                ;; boundary while it is busy complying.
                (> due last-fired))))
    :message (fn [_] (prompt "repopulate"))
    :prediction (fn [_] "the branch calls branch_theses")
    ;; The one tool this prediction names, so the steer can be prefilled
    ;; rather than suggested. Only set where the prediction is
    ;; unambiguous: a gate predicting "review or done" must not have the
    ;; harness pick for the branch.
    :tool "branch_theses"
    :window 3}

   {:gate :stuck
    :priority 8
    :budget :max-stuck-hints
    :doc "Consecutive failed or repetitive verifications. Keyed on failure,
          which is why the progress gate below exists as well.

          The one gate that changes branch state rather than only speaking.
          It used to append a hint and nothing else, and settled 0 met across
          every generation that recorded it — for two reasons, both fixed
          together. It fired at cull-threshold, so the advice to change course
          arrived on the turn the branch became killable for not having
          changed it (vf-31m); stuck-threshold is now strictly lower and the
          firing opens a grace window. And it merely suggested, where this
          harness's one reliably-working gate is the one that WITHHOLDS
          (vf-49o): the loop answers a firing by calling state/begin-reframe,
          after which re-verifying the failing approach is refused on every
          engine until the branch puts a different one on record.

          A gate is data and cannot mutate the branch, so the effect lives in
          the loop beside the turn-budget bookkeeping. The ordering that makes
          it work is the beam's: every branch advances before any is culled,
          so the reprieve is in place by the time retention is decided on the
          same turn."
    :when (fn [{:keys [branch]}]
            (>= (:consecutive-failures branch) (threshold :stuck-threshold)))
    :message (fn [{:keys [branch]}]
               (str (prompt "stuck")
                    (when-let [c (:last-failed-claim branch)]
                      (str "\n\n**Withheld**, until you put a different approach"
                           " on record:\n\n> " c "\n\nThat claim will not reach an"
                           " engine — any engine — while this stands. Everything"
                           " else is open, including a smaller piece of the same"
                           " goal. The next " (threshold :reframe-grace) " turns are"
                           " yours to change course in; you will not be culled for"
                           " the failures that led here."))))
    :prediction (fn [_] "the branch retracts, decomposes, or changes technique")
    :window 3}

   {:gate :prologue-cap
    :priority 9
    :budget nil
    :doc "The branch has produced nothing at all. Every other guard is
          principled-blind here: the stall counter arms on a progress event, the
          stuck gate needs failures, and a branch making successful, varied,
          useless calls trips neither."
    ;; Build phase only (vf-9wx). A banked sketch is deliberately :neutral
    ;; with progress? false — a plan is not progress — so a branch dropped
    ;; back into :explore by a reframe accrues nothing while it re-plans, and
    ;; without this guard is told at turn 41 that it is 41 turns in with
    ;; nothing verified. True, and useless to a branch doing exactly what the
    ;; harness just asked of it. The ordering is right by construction: a
    ;; branch that sketches at once gets the full build allowance before the
    ;; nudge, one that burns its whole explore budget gets what is left, so
    ;; the flailing branch gets LESS rope rather than more. (Counting from
    ;; build entry instead would invert that, which is why it is not done.)
    :when (fn [{:keys [branch]}]
            (and (= :build (:phase branch))
                 (not (:any-progress? branch))
                 (>= (state/turn-count branch) (threshold :prologue-cap))))
    :message (fn [{:keys [branch]}]
               (str (prompt "prologue-cap")
                    "\n\nYou are " (state/turn-count branch)
                    " turns in with nothing verified."))
    :prediction (fn [_] "the branch produces its first artifact")
    :window 3}

   {:gate :progress-stalled
    :priority 10
    :budget :max-stall-nudges
    :doc "Turns passing with no progress event, after the branch has shown it
          can make progress. Arms only after the first, so exploration is never
          nudged."
    :when (fn [{:keys [branch]}]
            (and (:any-progress? branch)
                 (>= (:turns-since-progress branch)
                     (threshold :progress-stall-threshold))))
    :message (fn [{:keys [branch]}]
               (str (prompt "progress-stalled")
                    "\n\nNothing has advanced in " (:turns-since-progress branch)
                    " turns."))
    :prediction (fn [_] "the branch produces a new confirmed artifact or discharges a sub-claim")
    :window 3}

   {:gate :tier-escalation
    :priority 11
    :budget :max-tier-escalations
    :doc "Artifacts exist but only from the fast tier. A one-shot check and a
          cross-checked template are not the same evidence, and at finalization
          the difference is the whole question (DS1's fidelity pyramid)."
    :when (fn [{:keys [branch]}]
            (and (>= (count (:artifacts branch)) (threshold :fast-verify-edit-threshold))
                 (not (contains? (:tiers-seen branch) :slow))))
    :message (fn [_] (prompt "tier-escalation"))
    :prediction (fn [_] "the branch runs a slow-tier check")
    :window 3}

   {:gate :turn-budget
    :priority 12
    :budget nil
    :doc "The turn cap was enforced but invisible to the model, so it could not
          budget against it (dirge PR 738)."
    ;; Reads the branch's own record of which fractions it has been told
    ;; about. Taking it from the context instead meant the caller had to
    ;; remember to thread it back, and on the first live run it did not — the
    ;; gate re-fired on every turn past 60%.
    :when (fn [{:keys [branch max-turns]}]
            (let [used (/ (double (state/turn-count branch)) (max 1 max-turns))
                  notified (:notified-fractions branch #{})]
              (boolean (some #(and (>= used %) (not (contains? notified %)))
                             (threshold :turn-budget-notices)))))
    :message (fn [{:keys [branch max-turns]}]
               (str "**Turn budget**: you have used " (state/turn-count branch)
                    " of " max-turns " turns. Land what you can verify rather"
                    " than opening a new line of attack."))
    :prediction (fn [_] "the branch converges rather than starting something new")
    :window 3}])

(def by-name (into {} (map (juxt :gate identity)) gates))

(defn crossed-fractions
  "Which turn-budget notice thresholds this branch has now passed. The loop
  folds these into the branch so the gate stops re-firing."
  [branch max-turns]
  (let [used (/ (double (state/turn-count branch)) (max 1 max-turns))]
    (set (filter #(>= used %) (threshold :turn-budget-notices)))))

(defn budget-exceeded?
  "Whether this gate has already fired as often as it may."
  [gate branch]
  (when-let [k (:budget gate)]
    (>= (fired-count branch (:gate gate)) (threshold k))))

(defn describe
  "The gate table, for docs and for /v1/harness/state."
  []
  (for [g gates]
    {:gate (:gate g) :priority (:priority g)
     :budget (:budget g)
     :budget-kind (some-> (:budget g) (#(get-in (config) [% :kind])))
     :doc (str/replace (str/trim (:doc g)) #"\s+" " ")}))
