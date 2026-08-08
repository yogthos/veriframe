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
  ;; A KNOWN LIMITATION, pinned so nobody assumes otherwise.
  ;;
  ;; a#321 and a#324 both said "the following 14 inequalities" and then listed
  ;; three families totalling 3+5+7 = 15. The encodings had all fifteen; only
  ;; the prose was wrong. This check cannot catch that, because an encoding
  ;; legitimately carries asserts the claim never counts — domain bounds,
  ;; symmetry breaks, sort constraints — so "more than stated" is exactly what
  ;; a correct artifact looks like. Only the deficit direction is decidable
  ;; from a count, and only the deficit direction is dangerous: it means
  ;; constraints the claim promises are absent from what the engine answered.
  (let [asserts (clojure.string/join "\n" (repeat 15 "(assert (>= (+ 1 2) 3))"))
        r (f/check-smt "no assignment satisfies all of the following 14 inequalities"
                       (str asserts "\n(check-sat)"))]
    (is (true? (:ok r)))))
