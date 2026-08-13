;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.arbiter
  "One arbiter per boundary, emitting at most one message.

  The TypeScript harness injects its milestone prompt, its emergency-review
  prompt and its stuck hint from three independent conditionals, so a branch
  can receive three messages before a single turn, each pushing a different
  direction. dirge found up to five and replaced the chain with one arbiter
  choosing in strict priority. That is the behavior-tree fallback node, and it
  is the single highest-value structural change available here.

  Two properties follow and both are asserted in the tests.

  Exactly one steer per boundary, always. Not zero-or-one per gate.

  A gate that fires records what it expects to happen next, and a later turn
  settles that prediction from the journal with no model in the path. A gate
  whose predictions never settle is not steering anything, and that is
  measurable rather than arguable."
  (:require [veriframe.agent.gates :as gates]
            [veriframe.agent.state :as state]))

(defn eligible
  "Every gate whose precondition holds and whose budget is not spent, in
  priority order. Exposed so a test can assert what the arbiter passed over,
  not only what it chose."
  [ctx]
  (->> gates/gates
       (filter (fn [g] (and ((:when g) ctx)
                            (not (gates/budget-exceeded? g (:branch ctx))))))
       (sort-by :priority)))

(defn decide
  "Pick at most one steer for this boundary.

  Returns nil when nothing applies, or {:gate :message :prediction :window
  :priority :passed-over}. `:passed-over` names the gates that also held, which
  is what makes the gate tally's co-occurrence column readable — a gate that
  only ever fires alone tells you something different from one that is
  perpetually outranked."
  [ctx]
  (let [candidates (eligible ctx)]
    (when-let [chosen (first candidates)]
      {:gate (:gate chosen)
       :priority (:priority chosen)
       :message ((:message chosen) ctx)
       :prediction ((:prediction chosen) ctx)
       :window (:window chosen)
       :passed-over (mapv :gate (rest candidates))})))

;; --- settling predictions ---------------------------------------------------

(defn- progressed? [before after]
  (or (> (count (state/confirmed-artifacts after))
         (count (state/confirmed-artifacts before)))
      (> (count (:artifacts after)) (count (:artifacts before)))))

(defn settle
  "Decide whether an open prediction came true, given what the branch did.

  Deterministic and cheap: the questions are all of the form 'did the branch
  call one of these tools' or 'did it produce something new'. No model is
  consulted, because a judge asked to grade the harness's own steering is a
  judge with an opinion about the harness.

  Returns :met, :unmet, or nil for still-open."
  [{:keys [gate turn window]} {:keys [current-turn tools-called branch-before branch-after]}]
  (let [expired? (>= (- current-turn turn) window)
        called? (fn [& names] (boolean (some (set names) tools-called)))]
    (cond
      (case gate
        :milestone (called? "review" "done" "verify_template")
        :branch-out (called? "branch_theses")
        :repopulate (called? "branch_theses")
        :emergency-review (called? "review" "done" "thesis")
        :stuck (called? "retract_rule" "thesis" "add_rule")
        :prologue-cap (progressed? branch-before branch-after)
        :progress-stalled (progressed? branch-before branch-after)
        :tier-escalation (called? "verify_template" "review" "audit")
        :done-blocked (called? "done" "give_up" "audit" "review")
        :human-directive true
        :turn-budget (called? "done" "give_up" "review" "audit")
        :wind-down (called? "review" "audit" "done")
        false)
      :met

      expired? :unmet
      :else nil)))
