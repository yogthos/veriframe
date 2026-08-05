(ns veriframe.bench.problems
  "The benchmark registry.

  Two kinds of entry, and the distinction is the whole point of PLAN.md's
  probe-set section. A `:difficulty` problem asks whether the harness is any
  good. A `:probe` problem asks whether a specific mechanism works, and names
  the gate or classification it exists to exercise plus what the harness must
  NOT do. The second kind is what tells you the thing is working; the first
  only tells you how well.

  `:expect` is the harness-level assertion, checked by the runner against the
  journal rather than by reading a transcript:

    :answered   a verified answer shipped
    :refused    no answer shipped, which for an unsatisfiable problem is
                the CORRECT outcome
    :gate       this gate must have fired
    :never-gate this gate must NOT have fired
    :status     an artifact with this claim_status must exist
    :contains   the answer must contain this string"
  (:require [clojure.string :as str]))

(def problems
  {;; --- liveness: the floor -------------------------------------------------
   "knights-3"
   {:kind :difficulty :engines #{:prolog}
    :statement (str "Three inhabitants of an island of knights (who always tell the truth)"
                    " and knaves (who always lie): A, B and C. A says: B is a knave."
                    " B says: A and C are the same type. C says: A is a knight."
                    " Determine the type of each, verified in Prolog.")
    :expect {:answered true :contains "knave"}
    :note "The floor. If this stops closing, something regressed."}

   "zebra-5x5"
   {:kind :difficulty :engines #{:prolog}
    :statement (str "Five houses in a row, each a different colour, with owners of"
                    " different nationalities who drink different drinks, smoke different"
                    " brands and keep different pets. The Englishman lives in the red"
                    " house. The Spaniard owns the dog. Coffee is drunk in the green"
                    " house. The Ukrainian drinks tea. The green house is immediately"
                    " right of the ivory house. The Old Gold smoker owns snails. Kools"
                    " are smoked in the yellow house. Milk is drunk in the middle house."
                    " The Norwegian lives in the first house. The Chesterfields smoker"
                    " lives next to the fox owner. Kools are smoked next to the horse"
                    " owner. The Lucky Strike smoker drinks orange juice. The Japanese"
                    " smokes Parliaments. The Norwegian lives next to the blue house."
                    " Who drinks water and who owns the zebra? Use library(clpfd).")
    :expect {:answered true :contains "zebra"}
    :note "A large CLP(FD) encoding built across several add_rule calls."}

   "pythag-1000"
   {:kind :difficulty :engines #{:smt}
    :statement (str "Find positive integers a < b < c with a^2 + b^2 = c^2 and"
                    " a + b + c = 1000. Verify with Z3.")
    :expect {:answered true :contains "200"}
    :note "One small Z3 call. Both engines agree on 200/375/425 (Phase 1)."}

   "math-induction-pow2-gt-n"
   {:kind :difficulty :engines #{:lean}
    :statement "Prove that for every natural number n, n < 2^n. Use Lean 4 and Mathlib."
    :expect {:answered true}
    :note "The Phase 5 deliverable. Closes in one tactic if the model finds it."}

   ;; --- false positives: the probes that matter most ------------------------
   "sidon-40-in-100"
   {:kind :probe :engines #{:smt}
    :statement (str "Find a Sidon set of size 40 inside [1,100] — a set whose pairwise"
                    " sums a+b for a <= b are all distinct — and verify it.")
    :expect {:refused true}
    :note (str "No such set exists (the maximum near 100 is around 13). The correct"
               " outcome is give_up or a refutation. A shipped answer is a harness"
               " failure regardless of what the model said, and this is the probe that"
               " catches a gate relaxed too far.")}

   "existential-trap"
   {:kind :probe :engines #{:smt}
    :statement (str "Show that a 4-colouring of [1,40] with no monochromatic solution to"
                    " x + y = z exists, and give the colouring. Verify it with Z3.")
    :expect {:status "existential"}
    :note (str "The natural encoding leaves the colouring free, so SAT proves one"
               " EXISTS without pinning it. The artifact must land in the existential"
               " bucket and must not substantiate a concrete answer.")}

   ;; --- gate provocations, live ---------------------------------------------
   "milestone-early-result"
   {:kind :probe :engines #{:smt}
    :statement (str "Find the largest Sidon set you can inside [1,50] and verify it."
                    " Report the set and its size.")
    :expect {:gate :milestone}
    :note (str "A small result verifies early while the goal invites pushing further."
               " The milestone must fire exactly once, not once per later turn.")}

   "prologue-exploration"
   {:kind :probe :engines #{:lean}
    :statement (str "What is the largest known cap set in F_3^6, and who found it?"
                    " Discuss the state of the art.")
    :expect {:gate :prologue-cap :never-gate :progress-stalled}
    :note (str "Phrased to invite searching rather than verifying. A branch can burn"
               " turns in lean_search producing nothing, and the prologue bound is the"
               " only guard that can see it — the stall counter arms on a progress"
               " event that never happens.")}

   "tier-escalation-fast-only"
   {:kind :probe :engines #{:smt :prolog}
    :statement (str "Show that no three distinct elements of {1, 2, 4, 8, 16} form an"
                    " arithmetic progression. Verify it.")
    :expect {:gate :tier-escalation}
    :note (str "Closable with fast one-shot checks alone, so the end-of-run escalation"
               " to the slow tier should fire once.")}

   ;; --- harder, from the original registry ----------------------------------
   "math-gauss-sum"
   {:kind :difficulty :engines #{:lean}
    :statement (str "Prove that the sum of the first n positive integers equals"
                    " n(n+1)/2. Use Lean 4 and Mathlib.")
    :expect {:answered true}}

   "math-sqrt-2-irrational"
   {:kind :difficulty :engines #{:lean}
    :statement "Prove that the square root of 2 is irrational. Use Lean 4 and Mathlib."
    :expect {:answered true}}

   "pigeonhole-3-2"
   {:kind :difficulty :engines #{:prolog :smt}
    :statement (str "Show that any assignment of three items to two boxes puts at least"
                    " two items in the same box. Verify it.")
    :expect {:answered true}}

   "cap-set-f3-3"
   {:kind :difficulty :engines #{:smt}
    :statement (str "Find a cap set in F_3^3 of size 9 — a subset with no three distinct"
                    " elements summing to zero componentwise mod 3 — and verify it with"
                    " the cap_set_f3n template.")
    :expect {:answered true}}

   "no-3ap-subset-20"
   {:kind :difficulty :engines #{:smt}
    :statement (str "Find a subset of [1,20] of size 8 containing no three-term"
                    " arithmetic progression, and verify it.")
    :expect {:answered true}}})

(defn by-kind [k] (into {} (filter #(= k (:kind (val %))) problems)))
(defn probes [] (by-kind :probe))
(defn requiring-engine [e]
  (into {} (filter #(contains? (:engines (val %)) e) problems)))

(defn describe []
  (str/join "\n"
            (for [[id p] (sort-by key problems)]
              (format "  %-28s %-11s %s" id (name (:kind p))
                      (str/join "," (map name (sort (:engines p))))))))
