;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.faithful-test
  "Deterministic claim-vs-encoding checks, driven by the artifacts that got
  through.

  Every false artifact the campaign shipped was caught by a human reading the
  encoding, never by a gate. The LLM judge helps — it blocked three defective
  encodings in gen-12 and drove a branch to a correct one — but it also passed
  the first instance of a defect it later caught, and it cannot count. These
  checks are arithmetic: they fire with certainty or not at all, they cost
  nothing, and they run before the judge."
  (:require [clojure.test :refer [deftest testing is]]
            [veriframe.agent.faithful :as f]))

;; --- prolog: constraints posted where they get undone ------------------------

(deftest constraints-inside-findall-are-rejected
  ;; gen-13 a#333. Posted every class constraint inside findall/3, which runs
  ;; its goal in a separate context and discards bindings AND constraint posts.
  ;; What survived was `Row ins 0..1, sum(Row,#=,1)` — pick one value per row —
  ;; which any assignment satisfies. Ten instant solutions, empty witnesses,
  ;; recorded as proof of a major result.
  (let [r (f/check-prolog
           "the q=105 layered condition is satisfiable"
           "sat_105 :- length(Rows,3), Rows ins 0..1,
              findall(_, (between(0,104,C), scalar_product(Cs,Bs,#>=,L)), _),
              labeling([ff],Flat).")]
    (is (false? (:ok r)))
    (is (some #(re-find #"(?i)findall" %) (:warnings r))
        "the warning names the construct that swallowed the constraints")))

(deftest constraints-inside-forall-are-rejected
  ;; gen-13 a#338, the same bug one construct over. forall/2 is \+ (C, \+ A),
  ;; a double negation, so posts inside it are undone just the same. Proven in
  ;; swipl: `Xs ins 0..1, forall(member(X,Xs), X #>= 1), label(Xs)` yields
  ;; [0,0,0], while posting directly yields [1,1,1].
  ;; Note the indirection: the constraint is posted by class_ok_reif, not
  ;; inline, which is why looking only inside the forall scope sees nothing.
  ;; Since a prolog artifact now carries its whole assert log, the definition
  ;; travels with the goal and can be read.
  (let [r (f/check-prolog
           "the mod-15 condition for P_3000 is satisfiable"
           "class_ok_reif(C,L,G1,Ys,Ts,Us) :- scalar_product(Coeffs,Bools,#>=,Need).
            p3000_sat_reif :- length(Ys,21), Ys ins 0..14,
              forall(between(0,14,C), class_ok_reif(C,L,G1,Ys,Ts,Us)),
              labeling([ff,bisect], All).")]
    (is (false? (:ok r)))
    (is (some #(re-find #"(?i)forall" %) (:warnings r)))))

(deftest a-clean-prolog-program-passes
  ;; Constraints posted directly, then labelled. Nothing to object to.
  (let [r (f/check-prolog
           "there is no assignment"
           "ok :- length(Xs,3), Xs ins 0..1,
              maplist([X]>>(X #>= 1), Xs), label(Xs).")]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

(deftest findall-without-constraints-is-fine
  ;; findall collecting ground solutions is ordinary Prolog, not the bug.
  ;; The check must not fire on it or it will be ignored.
  (let [r (f/check-prolog
           "collect the divisors"
           "divs(L) :- findall(D, (between(1,100,D), 0 is 100 mod D), L).")]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

;; --- smt: a coefficient that matches no modulus ------------------------------

(deftest a-coefficient-explicable-by-no-modulus-is-rejected
  ;; gen-13 a#343 used 17361625 where 15L/2835 = 17364375. 15L/17361625 is not
  ;; an integer, so the number corresponds to no modulus at all — a typo that
  ;; nothing noticed. Every other coefficient in that file was of the form
  ;; g*L/m, which is what makes the odd one out detectable.
  ;; Coefficients are written in the position real encodings use — multiplying
  ;; a variable — because only those are of the form g*L/m. See the companion
  ;; test on aggregate constants for why that distinction is load-bearing.
  (let [L 3281866875
        good [(quot (* 15 L) 45) (quot (* 15 L) 2835)
              (quot (* 3 L) 9) (quot (* 5 L) 25) (quot L 7)]
        term (fn [c i] (str "(ite (= y" i " 0) " c " 0)"))
        smt (str "(assert (>= (+ "
                 (clojure.string/join " " (map term good (range)))
                 " " (term 17361625 99) ") " L "))")
        r (f/check-smt "layered condition with L = 3281866875" smt)]
    (is (false? (:ok r)))
    (is (some #(re-find #"17361625" %) (:warnings r))
        "the warning names the specific unexplainable coefficient")))

(deftest aggregate-constants-are-not-mistaken-for-coefficients
  ;; This rejected a VERIFIED-correct artifact before it was narrowed. In the
  ;; P_3000 encoding, 546750000 is the sum of the four g=1 contributions
  ;; (L/7 + L/49 + L/343 + L/2401) and 2735116875 is the threshold L minus it.
  ;; Neither belongs to any single modulus, and neither should: they are a
  ;; bare addend and a comparison right-hand side, not coefficients. A gate
  ;; that blocks correct work gets routed around, so this case is pinned.
  (let [L 3281866875
        good [(quot (* 15 L) 45) (quot (* 15 L) 2835)
              (quot (* 3 L) 9) (quot (* 5 L) 25) (quot L 7)]
        term (fn [c i] (str "(ite (= y" i " 0) " c " 0)"))
        smt (str "(assert (>= (+ 546750000 "
                 (clojure.string/join " " (map term good (range)))
                 ") 2735116875))")
        r (f/check-smt "layered condition with L = 3281866875" smt)]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

(deftest coefficients-all-of-the-right-shape-pass
  (let [L 3281866875
        cs [(quot (* 15 L) 15) (quot (* 15 L) 45) (quot (* 15 L) 2835)
            (quot (* 3 L) 9) (quot (* 5 L) 25) (quot L 7)]
        smt (str "(assert (>= (+ "
                 (clojure.string/join " " (map #(str "(* " % " x" %2 ")") cs (range)))
                 ") " L "))")
        r (f/check-smt "layered condition with L = 3281866875" smt)]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

;; --- claim and encoding must agree on how many things there are --------------

(deftest a-claim-about-more-classes-than-the-encoding-asserts-is-rejected
  ;; gen-13 a#343 again: the claim described a condition over 15 classes and
  ;; the encoding asserted exactly one class constraint. Z3's sat was about a
  ;; question nobody asked. Counting is not something a judge does reliably;
  ;; it is something a machine does perfectly.
  (let [r (f/check-smt
           "every class c=0..14 must reach the threshold, so all 15 class inequalities hold"
           "(assert (>= (+ 1 2 3) 10))\n(check-sat)")]
    (is (false? (:ok r)))
    (is (some #(re-find #"15" %) (:warnings r)))))

(deftest a-claim-whose-count-matches-passes
  (let [asserts (clojure.string/join "\n" (repeat 15 "(assert (>= (+ 1 2) 3))"))
        r (f/check-smt "all 15 class inequalities hold for c=0..14"
                       (str asserts "\n(check-sat)"))]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

(deftest an-encoding-with-more-asserts-than-the-claim-states-is-allowed
  ;; A KNOWN LIMITATION of the ASSERT count, pinned so nobody assumes
  ;; otherwise. An encoding legitimately carries asserts the claim never counts
  ;; — domain bounds, symmetry breaks, sort constraints — so "more than
  ;; stated" is exactly what a correct artifact looks like. Only the deficit
  ;; direction is decidable from a count, and only the deficit direction is
  ;; dangerous: it means constraints the claim promises are absent from what
  ;; the engine answered.
  ;;
  ;; The surplus direction is reachable from the CLAIM alone instead — see
  ;; a-claim-whose-own-parts-outnumber-its-total-is-rejected below, which is
  ;; what actually catches a#321 and a#324.
  (let [asserts (clojure.string/join "\n" (repeat 15 "(assert (>= (+ 1 2) 3))"))
        r (f/check-smt "no assignment satisfies all of the following 14 inequalities"
                       (str asserts "\n(check-sat)"))]
    (is (true? (:ok r)))))

;; --- the claim has to add up on its own terms --------------------------------

(deftest a-claim-whose-own-parts-outnumber-its-total-is-rejected
  ;; gen-12 a#321 and a#324, verbatim in shape: "all of the following 14
  ;; inequalities" followed by three families indexed 0..2, 0..4 and 0..6 —
  ;; 3 + 5 + 7 = 15. Both encodings carried all fifteen, so only the prose was
  ;; wrong, and no comparison against the encoding can see it. The claim
  ;; contradicts itself, which is arithmetic on the claim text alone.
  ;;
  ;; Only the SURPLUS direction fires. A claim may name families and then add
  ;; constraints it never enumerates, so a shortfall proves nothing — but
  ;; enumerated parts can only be a subset of the whole, so parts exceeding the
  ;; stated total is a certain contradiction.
  (let [r (f/check-claim
           (str "For D={3,5,7,9,15,21,27,35,45,63,105,135,189,315,945}, there is no"
                " assignment of integers a_m with 0<=a_m<m for each m in D such that"
                " ALL of the following 14 inequalities hold: for each r in {0,1,2},"
                " 351 + ... >= 945; for each r in {0,1,2,3,4}, 662 + ... >= 945;"
                " for each r in {0,..,6}, 735 + ... >= 945."))]
    (is (false? (:ok r)))
    (is (some #(re-find #"15" %) (:warnings r))
        "the warning states what the parts actually add up to")))

(deftest a-modulus-set-is-not-mistaken-for-an-index-family
  ;; The same claim carries D={3,5,7,9,...,945} — fifteen elements, in a
  ;; `for each m in D` phrase. If that set were counted as an index family the
  ;; check would fire on every claim of this shape whatever the count said.
  ;; Index families run from 0; a set of moduli does not.
  (let [r (f/check-claim
           (str "For D={3,5,7,9,15,21,27,35,45,63,105,135,189,315,945} there is no"
                " assignment satisfying the following 2 inequalities: for each r in"
                " {0,1}, sum over m in D of w_m >= 945."))]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

(deftest enumerated-families-within-the-stated-total-pass
  ;; 3 + 5 = 8 against a stated 12. The claim may well have four more
  ;; constraints it did not spell out; nothing here contradicts anything.
  (let [r (f/check-claim
           (str "all 12 constraints hold: for each r in {0,1,2}, A_r >= L;"
                " for each s in {0,...,4}, B_s >= L"))]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

(deftest a-claim-with-no-stated-total-is-left-alone
  (let [r (f/check-claim "for each r in {0,1,2}, A_r >= L")]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

;; --- octave: a verdict that read nothing the engine computed -----------------

(deftest an-octave-check-over-literals-only-is-rejected
  ;; gen-13 a#344, a CONFIRMED artifact whose entire code is `1.014488 > 1`.
  ;; The glpk solve that produced 1.014488 happened on an earlier turn and is
  ;; nowhere in the artifact; Octave was handed a number the model typed and
  ;; asked to compare it to another. The verdict is true and says nothing —
  ;; whatever the claim, this expression would have confirmed it.
  (let [r (f/check-octave "the LP dual certificate value is 1.014488 > 1"
                          "1.014488 > 1")]
    (is (false? (:ok r)))
    (is (some #(re-find #"(?i)computed nothing" %) (:warnings r)))))

(deftest an-octave-check-that-reads-the-workspace-passes
  ;; a#346, a#347 and a#348 from the same run, which do reference what was
  ;; computed. The check must let these through or it is useless.
  (doseq [expr ["bad == 0 && total == 225"
                "total == 225 && bad == 0"
                "vf_approx(slack, 35/48, 1e-9)"]]
    (let [r (f/check-octave "every one of the 225 cases has positive slack" expr)]
      (is (true? (:ok r)) (str expr " → unexpected warnings: " (:warnings r))))))

(deftest octave-as-a-calculator-over-literals-still-computes
  ;; The first cut of this check fired on any expression without a variable,
  ;; which rejected four correct artifacts to catch a#344. These are all real
  ;; confirmed artifacts from the campaign, and in every one the engine does
  ;; the arithmetic the claim is about. The defect in a#344 is narrower: a
  ;; comparison with no operation in it at all.
  (doseq [expr ["(45045 - 32805 == 12240)"
                "(1/3+1/5+1/7+1/9+1/11+1/13+1/15+1/25) > 1"
                "all([315/3,315/5,315/7,315/9] == [105,63,45,35])"
                "abs(1.5 - 1.0) < 0.001"
                "sum([1,2,3]) == 6"]]
    (let [r (f/check-octave "the arithmetic holds" expr)]
      (is (true? (:ok r)) (str expr " → unexpected warnings: " (:warnings r))))))

(deftest octave-builtin-constants-are-not-operands-either
  ;; `true` is a constant of the language, not something the workspace worked
  ;; out, so this comparison is as empty as a#344's.
  (let [r (f/check-octave "the bound holds" "true && 3 > 2")]
    (is (false? (:ok r)))))

;; --- smt: an unsat that only searched part of the space ----------------------

(deftest an-unsat-over-a-partly-pinned-family-is-rejected
  ;; gen-13 a#336 recorded a REFUTATION of "the mod-15 condition for P_3000 is
  ;; satisfiable" from an encoding containing `(assert (= y0 0))`. Z3's unsat
  ;; was over the slice with y0 = 0, and the claim quantified over all of them.
  ;; Whether pinning y0 is a sound symmetry break is a mathematical argument
  ;; that is nowhere in the file.
  ;;
  ;; The tell is that the pin covers PART of a family: y0 is fixed while
  ;; y1..y20 are free. That is a restriction of the search space, never a
  ;; definition.
  (let [decls (clojure.string/join
               "\n" (for [i (range 21)] (str "(declare-fun y" i " () Int)")))
        smt (str decls "\n(assert (= y0 0))\n(assert (>= (+ y1 y2) 3))\n(check-sat)")
        r (f/check-smt "there exist y_i with every class covered" smt {:verdict :unsat})]
    (is (false? (:ok r)))
    (is (some #(re-find #"y0" %) (:warnings r))
        "the warning names the pin that shrank the space")))

(deftest a-pin-the-claim-discloses-is-left-to-the-judge
  ;; Three artifacts in the same run pin y0 out of y0..y20. a#334 and a#337 say
  ;; so — "fixes y0 = 0 by the class-permutation symmetry" — and a#336 does
  ;; not, and a#336 is the one whose unsat did not survive removing the pin by
  ;; hand. Whether a DISCLOSED reduction is sound is a mathematical argument
  ;; about the problem, which is the reviewer's job; objecting to it here would
  ;; be guessing, and a check that guesses gets routed around.
  (let [decls (clojure.string/join
               "\n" (for [i (range 21)] (str "(declare-fun y" i " () Int)")))
        smt (str decls "\n(assert (= y0 0))\n(assert (>= (+ y1 y2) 3))\n(check-sat)")
        r (f/check-smt (str "no assignment of the y_i covers every class, with y0"
                            " fixed to 0 by the class-permutation symmetry")
                       smt {:verdict :unsat})]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

(deftest a-defined-constant-is-not-a-pinned-variable
  ;; `(assert (= L 3281866875))` defines a name. Nothing is excluded by it,
  ;; and an encoding that names its constants this way is doing the right
  ;; thing. Only a proper subset of a FAMILY reads as a restriction.
  (let [decls (clojure.string/join
               "\n" (for [i (range 21)] (str "(declare-fun y" i " () Int)")))
        smt (str "(declare-fun L () Int)\n" decls
                 "\n(assert (= L 3281866875))\n(assert (>= (+ y1 y2) L))\n(check-sat)")
        r (f/check-smt "there exist y_i with every class covered" smt {:verdict :unsat})]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

(deftest a-fully-pinned-family-is-not-a-restriction
  ;; Every y_i fixed is a ground check of one specific assignment, which is a
  ;; legitimate thing to ask and a claim can say so. Nothing is half-searched.
  (let [decls (clojure.string/join
               "\n" (for [i (range 5)] (str "(declare-fun y" i " () Int)")))
        pins (clojure.string/join
              "\n" (for [i (range 5)] (str "(assert (= y" i " " i "))")))
        r (f/check-smt "this specific assignment covers every class"
                       (str decls "\n" pins "\n(check-sat)") {:verdict :unsat})]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

(deftest a-pin-under-a-sat-verdict-is-not-objected-to
  ;; A pin narrows the space, so it can only make unsat prove less. A SAT
  ;; verdict hands back a real assignment, and an assignment found inside a
  ;; restricted space is still an assignment.
  (let [decls (clojure.string/join
               "\n" (for [i (range 21)] (str "(declare-fun y" i " () Int)")))
        smt (str decls "\n(assert (= y0 0))\n(assert (>= (+ y1 y2) 3))\n(check-sat)")
        r (f/check-smt "some assignment covers every class" smt {:verdict :sat})]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))

;; --- lean gets the claim-side checks too -------------------------------------

(deftest lean-claims-are-held-to-the-same-arithmetic
  (let [r (f/check-lean
           (str "all of the following 14 inequalities hold: for each r in {0,1,2},"
                " A_r >= L; for each s in {0,..,4}, B_s >= L; for each t in {0,..,6},"
                " C_t >= L")
           "theorem foo : True := trivial")]
    (is (false? (:ok r)))))

(deftest a-lean-snippet-with-a-consistent-claim-passes
  (let [r (f/check-lean "the sum of two odds is even"
                        "theorem foo (a b : Nat) : Even (2*a + 1 + (2*b + 1)) := by omega")]
    (is (true? (:ok r)) (str "unexpected warnings: " (:warnings r)))))
