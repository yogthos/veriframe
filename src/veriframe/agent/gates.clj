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

   {:gate :emergency-review
    :priority 3
    :budget nil
    :doc "At the cull threshold but holding a recent confirmation. Rather than
          culling the branch that produced the most, tell it to ship what it
          has or change approach."
    :when (fn [{:keys [branch]}]
            (and (>= (:consecutive-failures branch) (threshold :cull-threshold))
                 (state/confirmed-in-last branch (threshold :cull-recent-window))))
    :message (fn [_] (prompt "emergency-review"))
    :prediction (fn [_] "the branch calls review or done, or changes technique")
    :window 3}

   {:gate :milestone
    :priority 4
    :budget :max-milestone-nudges
    :doc "First confirmed artifact on this branch. Runs that do not ship at
          this moment usually fail: after a confirmation the instinct is to
          push for more, and that usually loses the verified result."
    :when (fn [{:keys [branch]}] (state/has-confirmed? branch))
    :message (fn [_] (prompt "milestone"))
    :prediction (fn [_] "the branch calls review or done within two turns")
    :window 2}

   {:gate :stuck
    :priority 5
    :budget :max-stuck-hints
    :doc "Consecutive failed or repetitive verifications. Keyed on failure,
          which is why the progress gate below exists as well."
    :when (fn [{:keys [branch]}]
            (>= (:consecutive-failures branch) (threshold :stuck-threshold)))
    :message (fn [_] (prompt "stuck"))
    :prediction (fn [_] "the branch retracts, decomposes, or changes technique")
    :window 3}

   {:gate :prologue-cap
    :priority 6
    :budget nil
    :doc "The branch has produced nothing at all. Every other guard is
          principled-blind here: the stall counter arms on a progress event, the
          stuck gate needs failures, and a branch making successful, varied,
          useless calls trips neither."
    :when (fn [{:keys [branch]}]
            (and (not (:any-progress? branch))
                 (>= (state/turn-count branch) (threshold :prologue-cap))))
    :message (fn [{:keys [branch]}]
               (str (prompt "prologue-cap")
                    "\n\nYou are " (state/turn-count branch)
                    " turns in with nothing verified."))
    :prediction (fn [_] "the branch produces its first artifact")
    :window 3}

   {:gate :progress-stalled
    :priority 7
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
    :priority 8
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
    :priority 9
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
