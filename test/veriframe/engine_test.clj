;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.engine-test
  "Phase 1: the engines, with no model in the loop.

  The phase check from PLAN.md is engine agreement — where both engines can
  express a problem, Prolog and Z3 have to reach the same answer. This is the
  last phase where a disagreement is unambiguously a harness bug rather than a
  model one, so it is worth spending the tests here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is are]]
            [veriframe.engine.lint :as lint]
            [veriframe.engine.proc :as proc]
            [veriframe.engine.octave :as octave]
            [veriframe.engine.prolog :as pl]
            [veriframe.engine.smt :as smt]
            [veriframe.engine.smt-templates :as tpl]))

;; --- lint -------------------------------------------------------------------

(deftest smt-lint
  (testing "a clean body passes"
    (is (:ok (lint/lint-smt "(declare-const x Int)\n(assert (> x 2))"))))

  (testing "a mid-line comment that eats an assertion is caught"
    ;; This is the n=500 Sidon false positive: Z3 SATs the constraint-free
    ;; formula that survives and the harness records it as verified.
    (let [r (lint/lint-smt "(declare-const x Int) ; (assert (> x 2))")]
      (is (not (:ok r)))
      (is (str/includes? (first (:warnings r)) "line comment"))))

  (testing "ellipsis shorthand is caught, but not inside a string literal"
    (is (not (:ok (lint/lint-smt "(declare-const a1 Int) ... (assert (> a1 0))"))))
    (is (:ok (lint/lint-smt "(set-option :dump \"a...b\")\n(assert true)"))))

  (testing "unbalanced parens are caught"
    (is (not (:ok (lint/lint-smt "(assert (> x 2)")))))

  (testing "pair-sum distinctness with no distinctness on the constants"
    (is (not (:ok (lint/lint-smt "(declare-const a1 Int)\n(assert (distinct (+ a1 a1) 3))")))))

  (testing "empty and comment-only bodies are rejected"
    (is (not (:ok (lint/lint-smt "   "))))
    (is (not (:ok (lint/lint-smt "; nothing here"))))))

(deftest prolog-lint
  (are [ok? code] (= ok? (:ok (lint/lint-prolog-program code)))
    true  "knight(alice)."
    false "knight(alice)"
    false "% only a comment"
    false "")
  (are [ok? goal] (= ok? (:ok (lint/lint-prolog-query goal)))
    true  "knight(X)"
    true  "?- knight(X)."
    false "?- ."
    false "")
  (testing "normalize strips the prefix and the terminator the session supplies"
    (is (= "knight(X)" (lint/normalize-query "?- knight(X).")))))

(deftest lean-lint
  (testing "sorry and admit compile with only a warning, so they must be blocked"
    (is (not (:ok (lint/lint-lean "theorem t : 1 = 1 := by sorry"))))
    (is (not (:ok (lint/lint-lean "example : 1 = 1 := by admit")))))
  (testing "a tactics-only snippet is not a declaration"
    (is (not (:ok (lint/lint-lean "simp\nring")))))
  (testing "a real declaration passes"
    (is (:ok (lint/lint-lean "theorem t : 1 = 1 := by rfl")))))

;; --- Z3 ---------------------------------------------------------------------

(deftest z3-verdicts
  (testing "unsat"
    (is (= :unsat (:verdict (smt/run-smt "(assert (= (+ 2 2) 5))")))))

  (testing "sat with a witness"
    (let [r (smt/run-smt "(declare-const x Int)(assert (= x 7))")]
      (is (= :sat (:verdict r)))
      (is (= "7" (get (:model r) "x")))))

  (testing "a Z3 parse error refuses the verdict rather than reporting it"
    ;; Z3 keeps running after (error ...) and prints sat for whatever parsed.
    (let [r (smt/run-smt "(declare-const a1 Int)\n(assert (> a1 zzz))")]
      (is (= :error (:status r)))
      (is (str/includes? (:error r) "parse/type error"))))

  (testing "the lint blocks execution before Z3 ever runs"
    (is (= :error (:status (smt/run-smt "(declare-const x Int) ; (assert (> x 2))"))))))

(deftest z3-model-parsing
  (testing "negative values are printed as (- N) and parsed back"
    (is (= {"x" "(- 5)"} (smt/parse-model "( (define-fun x () Int (- 5)) )"))))
  (testing "several bindings"
    (is (= {"a" "1" "b" "true"}
           (smt/parse-model "((define-fun a () Int 1)\n(define-fun b () Bool true))")))))

(deftest z3-witness-check
  (testing "a witness that violates an asserted bound is an encoding bug"
    (is (seq (smt/check-witness "(assert (>= x 1))" {"x" "0"})))
    (is (seq (smt/check-witness "(assert (<= x 10))" {"x" "11"}))))
  (testing "a witness that violates asserted distinctness is an encoding bug"
    (is (seq (smt/check-witness "(assert (distinct a b))" {"a" "3" "b" "3"}))))
  (testing "a consistent witness raises nothing"
    (is (empty? (smt/check-witness "(assert (>= x 1))(assert (distinct a b))"
                                   {"x" "5" "a" "1" "b" "2"})))))

;; --- templates --------------------------------------------------------------

(deftest sidon-template
  (testing "a Sidon set is confirmed by both encodings"
    (are [elements] (let [r (smt/run-template "sidon_set" {:elements elements})]
                      (and (:confirmed r) (:agreed r)))
      [1 2 5 11 13]
      [1 2 5]
      [1 3 11 15]))

  (testing "a non-Sidon set is refuted, and the two encodings still agree"
    ;; Regression. A Sidon (B_2) set has distinct pairwise sums for a <= b,
    ;; and the i = j case bites: {1,2,3,5} has 1+3 = 2+2 = 4. The TypeScript
    ;; original's cross-check asserts (< a b) against a primary enumerating
    ;; i <= j, so the two answer different questions and disagree on exactly
    ;; these sets. Caught by the cross-check on the first set tried.
    (are [elements] (let [r (smt/run-template "sidon_set" {:elements elements})]
                      (and (not (:confirmed r)) (:agreed r)))
      [1 2 3 5]
      [1 2 3]))

  (testing "bad slots are rejected before Z3 runs"
    (is (= :error (:status (smt/run-template "sidon_set" {:elements [1 1 2]}))))
    (is (= :error (:status (smt/run-template "sidon_set" {:elements []}))))))

(deftest other-templates
  (testing "no_3ap_subset"
    (is (:confirmed (smt/run-template "no_3ap_subset" {:elements [1 2 4 5]})))
    (is (not (:confirmed (smt/run-template "no_3ap_subset" {:elements [1 2 3]})))))

  (testing "schur_coloring — S(2) = 4, so [1,4] is 2-colorable and [1,5] is not"
    (is (:confirmed (smt/run-template "schur_coloring"
                                      {:n 4 :k 2 :coloring [1 2 2 1]})))
    (is (not (:confirmed (smt/run-template "schur_coloring"
                                           {:n 5 :k 2 :coloring [1 2 2 1 1]})))))

  (testing "cap_set_f3n"
    (is (:confirmed (smt/run-template "cap_set_f3n" {:n 2 :elements [0 1 3 4]})))
    ;; 0, 1, 2 are (0,0), (1,0), (2,0): they sum to zero component-wise.
    (is (not (:confirmed (smt/run-template "cap_set_f3n" {:n 2 :elements [0 1 2]})))))

  (testing "an unknown template lists what is available instead of throwing"
    (let [r (smt/run-template "nope" {})]
      (is (= :error (:status r)))
      (is (str/includes? (:error r) "sidon_set")))))

;; --- Prolog -----------------------------------------------------------------

(defmacro with-session [[binding] & body]
  `(let [~binding (pl/create-session)]
     (try ~@body (finally (pl/dispose! ~binding)))))

(def knights-rules
  "A statement made by a knight is true and one made by a knave is false.
   That is the whole puzzle; everything else is search."
  "
type(knight).
type(knave).
holds(knight, G) :- call(G).
holds(knave, G) :- \\+ call(G).
")

(def knights-3-goal
  ;; A: \"B is a knave\".  B: \"A and C are the same type\".  C: \"A is a knight\".
  ;; Unique solution: A knave, B knight, C knave.
  "type(A), type(B), type(C),
   holds(A, B = knave),
   holds(B, A = C),
   holds(C, A = knight)")

(def zebra-program
  "
zebra(WaterDrinker, ZebraOwner) :-
    Ns = [English, Spaniard, Ukrainian, Norwegian, Japanese],
    Cs = [Red, Green, Ivory, Yellow, Blue],
    Ds = [Coffee, Tea, Milk, Juice, Water],
    Ss = [OldGold, Kools, Chesterfields, LuckyStrike, Parliaments],
    Ps = [Dog, Snails, Fox, Horse, Zebra],
    append([Ns, Cs, Ds, Ss, Ps], Vars),
    Vars ins 1..5,
    all_different(Ns), all_different(Cs), all_different(Ds),
    all_different(Ss), all_different(Ps),
    English #= Red,
    Spaniard #= Dog,
    Coffee #= Green,
    Ukrainian #= Tea,
    Green #= Ivory + 1,
    OldGold #= Snails,
    Kools #= Yellow,
    Milk #= 3,
    Norwegian #= 1,
    abs(Chesterfields - Fox) #= 1,
    abs(Kools - Horse) #= 1,
    LuckyStrike #= Juice,
    Japanese #= Parliaments,
    abs(Norwegian - Blue) #= 1,
    label(Vars),
    Names = [english, spaniard, ukrainian, norwegian, japanese],
    nth0(WI, Ns, Water), nth0(WI, Names, WaterDrinker),
    nth0(ZI, Ns, Zebra), nth0(ZI, Names, ZebraOwner).
")

(deftest prolog-session-basics
  (with-session [s]
    (is (:ok (pl/ping s)))

    (testing "assert then query"
      (is (:ok (pl/assert-rules! s "knight(alice). knave(bob).")))
      (is (= [{:X "alice"}]
             (map :bindings (:answers (pl/query s "knight(X)"))))))

    (testing "a failed goal is ok with no answers — the claim is false, not broken"
      (let [r (pl/query s "knight(zed)")]
        (is (:ok r))
        (is (empty? (:answers r)))))

    (testing "a thrown goal is not ok — the encoding is broken, not the claim"
      (let [r (pl/query s "nosuchpredicate(X)")]
        (is (not (:ok r)))
        (is (str/includes? (:error r) "Unknown procedure"))))

    (testing "clpfd operators parse, which is what the bootstrap file is for"
      (is (= ["3" "4" "5"]
             (map (comp :X :bindings) (:answers (pl/query s "X #> 2, X #< 6, label([X])"))))))

    (testing "a ground goal that succeeds returns one empty binding set"
      (is (= [{}] (map :bindings (:answers (pl/query s "2 > 1"))))))))

(deftest prolog-named-rules
  (with-session [s]
    (pl/assert-rules! s "tmp(1)." {:name "t1"})
    (is (seq (:answers (pl/query s "tmp(X)"))))
    (is (:ok (pl/retract-rule! s "t1")))
    (is (empty? (:answers (pl/query s "tmp(X)"))))
    (testing "retract also drops the entry from the replay log"
      (is (empty? (pl/snapshot s))))))

(deftest prolog-timeout
  (with-session [s]
    (let [r (pl/query s "between(1, inf, X), X > 10, fail" {:timeout-s 2})]
      (is (not (:ok r)))
      (is (:timeout r)))
    (testing "the session survives a timed-out goal"
      (is (:ok (pl/ping s))))))

(deftest prolog-snapshot-restore
  (testing "a session is rebuilt by replaying its assert log into a fresh process"
    (let [s (pl/create-session)]
      (try
        (pl/assert-rules! s "a(1). a(2).")
        (pl/assert-rules! s "b(X) :- a(X), X > 1." {:name "b-rule"})
        (let [log (pl/snapshot s)
              restored (pl/restore log)]
          (try
            (is (= 2 (count log)))
            (is (= [{:X "2"}] (map :bindings (:answers (pl/query restored "b(X)")))))
            (finally (pl/dispose! restored))))
        (finally (pl/dispose! s))))))

(deftest prolog-knights-3
  (with-session [s]
    (is (:ok (pl/assert-rules! s knights-rules)))
    (let [answers (:answers (pl/query s knights-3-goal))]
      (is (= 1 (count answers)) "the puzzle has a unique solution")
      (is (= {:A "knave" :B "knight" :C "knave"} (:bindings (first answers)))))))

(deftest prolog-zebra
  (with-session [s]
    (is (:ok (pl/assert-rules! s zebra-program)))
    (let [answers (:answers (pl/query s "zebra(W, Z)" {:timeout-s 30}))]
      (is (= 1 (count answers)) "the puzzle has a unique solution")
      (is (= {:W "norwegian" :Z "japanese"} (:bindings (first answers)))))))

(deftest prolog-concurrent-sessions
  (testing "five sessions in parallel, which is what the beam holds"
    (let [sessions (repeatedly 5 pl/create-session)]
      (try
        (let [results (doall
                       (map deref
                            (map-indexed
                             (fn [i s]
                               (future
                                 (pl/assert-rules! s (str "v(" (* i 7) ")."))
                                 (-> (pl/query s "v(X)") :answers first :bindings :X)))
                             sessions)))]
          (is (= ["0" "7" "14" "21" "28"] results)))
        (finally (run! pl/dispose! sessions))))))

;; --- the phase check --------------------------------------------------------

(deftest engine-agreement
  (testing "Prolog and Z3 agree where both can express the problem"

    (testing "the Pythagorean triple summing to 1000"
      (let [z3 (smt/run-smt "(declare-const a Int)(declare-const b Int)(declare-const c Int)
(assert (> a 0))(assert (> b 0))(assert (> c 0))
(assert (< a b))(assert (< b c))
(assert (= (+ (* a a) (* b b)) (* c c)))
(assert (= (+ a b c) 1000))")
            from-z3 (mapv #(parse-long (get (:model z3) %)) ["a" "b" "c"])]
        (is (= :sat (:verdict z3)))
        (with-session [s]
          (let [answers (:answers (pl/query s "[A,B,C] ins 1..500, A #< B, B #< C,
                                               A*A + B*B #= C*C, A+B+C #= 1000,
                                               label([A,B,C])"
                                            {:timeout-s 30}))
                from-prolog (mapv #(parse-long (get-in (first answers) [:bindings %]))
                                  [:A :B :C])]
            (is (= 1 (count answers)) "the triple is unique")
            (is (= from-prolog from-z3)
                "the two engines returned different triples")
            (is (= [200 375 425] from-z3))))))

    (testing "a set is Sidon under Z3 iff Prolog's enumeration agrees"
      (with-session [s]
        (pl/assert-rules! s "
sums(S, Sums) :- findall(X, (member(A,S), member(B,S), A =< B, X is A+B), Sums).
sidon(S) :- sums(S, Sums), sort(Sums, Sorted), length(Sums, N), length(Sorted, N).
")
        ;; Commas, not Clojure's space-separated vector printing. SWI accepts
        ;; `[1 2 3 5]` without complaint and reads it as something other than a
        ;; four-element list, so the wrong rendering produces a confident wrong
        ;; answer rather than an error. Found by this very check.
        (doseq [elements [[1 2 5 11 13] [1 2 3 5] [1 2 5] [1 2 3]]]
          (let [as-list (str "[" (str/join "," elements) "]")
                prolog-says (boolean (seq (:answers (pl/query s (str "sidon(" as-list ")")))))
                z3-says (boolean (:confirmed (smt/run-template "sidon_set" {:elements elements})))]
            (is (= prolog-says z3-says)
                (str "engines disagree on whether " elements " is a Sidon set: "
                     "prolog=" prolog-says " z3=" z3-says))))))))

;; --- an abandoned request must not wedge the session ------------------------

(deftest busy-session-fails-fast-instead-of-blocking
  ;; The full benchmark died here. The beam abandons a branch turn that blows
  ;; its deadline, but the abandoned work keeps running; under `locking` it
  ;; kept the session monitor, so the NEXT turn blocked on it forever with no
  ;; timeout. The deadline fired exactly once and the branch wedged permanently,
  ;; taking the per-turn barrier and the whole run with it.
  (with-session [s]
    (is (:ok (pl/ping s)))
    (testing "a session marked busy refuses rather than waiting"
      (reset! (:busy s) true)
      (let [r (pl/query s "true")]
        (is (not (:ok r)))
        (is (str/includes? (:error r) "abandoned"))))
    (testing "and is killed, because its reply stream can no longer be framed"
      (is (not (pl/alive? s)))
      (is (not (:ok (pl/ping s)))))))

(deftest concurrent-callers-do-not-interleave
  ;; The flag has to actually exclude, not merely detect. Ten callers, one
  ;; session: every reply is either correct or an explicit refusal, and no
  ;; caller ever receives another's answer.
  (with-session [s]
    (pl/assert-rules! s "v(42).")
    (let [results (mapv deref (mapv (fn [_] (future (pl/query s "v(X)")))
                                    (range 10)))
          answered (filter :ok results)]
      (is (every? #(= "42" (get-in (first (:answers %)) [:bindings :X])) answered)
          "a caller that got an answer got ITS answer"))))

;; --- subprocess bounding ----------------------------------------------------

(deftest a-slow-subprocess-is-killed-at-its-timeout
  ;; This was unreachable for the life of the project. jolt's clojure.core/deref
  ;; forwards no opts to a record implementing IBlockingDeref, so the timed
  ;; (deref proc ms ::timeout) silently became the blocking one-arity and waited
  ;; for the process however long it took. The timeout branch never ran, every
  ;; engine call was unbounded, and the processes proc/run believed it was
  ;; killing accumulated -- 28 orphaned z3 processes on one machine, oldest at
  ;; seventeen hours.
  ;;
  ;; `sleep` rather than z3 so this is deterministic and needs no toolchain.
  (let [t0 (System/currentTimeMillis)
        r (proc/run {:timeout-ms 1000} "sleep" "30")
        elapsed (- (System/currentTimeMillis) t0)]
    (is (:timeout r) "a process past its budget must report a timeout")
    (is (< elapsed 15000)
        (str "must not wait for the process to finish on its own; took " elapsed "ms"))))

(deftest a-killed-subprocess-does-not-survive
  ;; The other half. Reporting a timeout while leaving the process running is
  ;; how the orphans accumulated in the first place, so assert it is gone rather
  ;; than assuming destroy worked. destroy-tree sends only SIGTERM; proc/run
  ;; escalates to SIGKILL because a process being killed for ignoring its
  ;; deadline is exactly the one that may ignore a polite signal.
  ;; Counting `sleep 45` specifically, not every sleep on the machine: any
  ;; unrelated process sleeping in the background (a shell watcher loop, say)
  ;; starting or ending between the two counts flakes a system-wide tally.
  (let [before (:out (proc/run {:timeout-ms 5000} "sh" "-c" "pgrep -f 'sleep 45' | wc -l"))
        t0 (System/currentTimeMillis)
        _ (dotimes [_ 3] (proc/run {:timeout-ms 500} "sleep" "45"))
        elapsed (- (System/currentTimeMillis) t0)]
    (Thread/sleep 1500)
    (let [after (:out (proc/run {:timeout-ms 5000} "sh" "-c" "pgrep -f 'sleep 45' | wc -l"))]
      (is (= (str/trim (str before)) (str/trim (str after)))
          (str "three killed `sleep 45` processes leaked; before=" before " after=" after))
      ;; Asserted explicitly because the count alone does not catch the original
      ;; bug: with the broken timed deref, proc/run blocked until each `sleep`
      ;; ended by itself, so nothing leaked here and the check passed after
      ;; sitting for 135 seconds. The leak in production came from the branch
      ;; being abandoned at its deadline while proc/run was still blocked, which
      ;; left the process with no one to reap it. Time is what distinguishes
      ;; killed from waited-for.
      (is (< elapsed 20000)
          (str "three 500ms timeouts should take about two seconds, not the full"
               " runtime of the processes; took " elapsed "ms")))))

(deftest a-fast-subprocess-still-returns-its-output
  (let [r (proc/run {:input "hello\n" :timeout-ms 5000} "cat")]
    (is (= 0 (:exit r)))
    (is (str/includes? (:out r) "hello"))
    (is (not (:timeout r)))))

;; --- Octave -----------------------------------------------------------------
;; Skipped rather than failed when octave is absent, like the Lean tests: it is
;; a fourth engine and the other three must stay testable without it.

(defn- with-octave [f]
  (if (octave/available?)
    (let [s (octave/create-session nil)]
      (try (f s) (finally (octave/dispose! s))))
    (println "  (skipping Octave tests: octave not on PATH)")))

(deftest octave-workspace-persists-across-calls
  ;; The workspace is a .mat file reloaded per invocation, and the first
  ;; implementation lost it: user code was eval'd inside a helper function, so
  ;; every variable became that function's local and vanished on return. A
  ;; branch could not build a problem up across turns, which is the whole point
  ;; of a workspace.
  (with-octave
    (fn [s]
      (is (:ok (octave/eval-code! s "A = [4 1; 1 3]; L = chol(A);")))
      (let [r (octave/check s "all(diag(L) > 0)")]
        (is (:ok r) (str "L should still exist on a later call: " (:error r)))
        (is (true? (:verdict r)))))))

(deftest octave-eval-accepts-multi-line-programs
  ;; Any real program is multi-line, and every one of them failed: the code
  ;; was re-quoted into an `evalc('...')` wrapper, and an Octave string
  ;; literal cannot span lines, so a comment header plus a loop came back as
  ;; a bare "syntax error" pointing at the harness's wrapper instead of the
  ;; model's code. B2 in the magic-square live run died of this at turn 2.
  ;; The code travels as data now and is never re-quoted.
  (with-octave
    (fn [s]
      (let [r (octave/eval-code! s (str "% comment header\n"
                                        "total = 0;\n"
                                        "for k = 1:10\n"
                                        "  total = total + k^2;\n"
                                        "endfor\n"))]
        (is (:ok r) (str "multi-line program should run: " (:error r))))
      (is (true? (:verdict (octave/check s "total == 385"))))
      (testing "quotes in the code survive, since nothing re-quotes them"
        (is (:ok (octave/eval-code! s "msg = 'it''s fine';"))))
      (testing "a genuine syntax error is still an error"
        (let [r (octave/eval-code! s "for k = 1:3\n  x = k +;\nendfor")]
          (is (not (:ok r)))
          (is (seq (str (:error r)))))))))

(deftest octave-refuses-anything-that-is-not-a-verdict
  ;; Coercing these is how a claim about every element silently becomes a claim
  ;; about one, or how "no answer" becomes "false".
  (with-octave
    (fn [s]
      (octave/eval-code! s "M = [1 2; 3 4];")
      (are [expr] (not (:ok (octave/check s expr)))
        "diag(M)"          ; a matrix, not a scalar
        "[]"               ; empty is not false
        "0/0"              ; NaN is not a verdict
        "nosuchvariable")  ; and neither is an error
      (is (true? (:verdict (octave/check s "det(M) != 0")))))))

(deftest octave-records-whether-a-verdict-was-exact
  ;; The distinction this engine turns on. A result true within a tolerance has
  ;; established something weaker than one true by exact arithmetic, and the
  ;; harness should not have to guess which it was handed.
  (with-octave
    (fn [s]
      (let [exact (octave/check s "1 + 1 == 2")]
        (is (true? (:verdict exact)))
        (is (true? (:exact exact))))
      (let [approx (octave/check s "vf_approx(0.1+0.1+0.1, 0.3, 1e-9)" 1e-9)]
        (is (true? (:verdict approx)))
        (is (false? (:exact approx)))
        (is (= 1e-9 (:tol approx))))
      ;; The trap the helper exists for: exact comparison of inexact arithmetic
      ;; is false, and a model reading that as a refutation would be wrong.
      (is (false? (:verdict (octave/check s "0.1+0.1+0.1 == 0.3")))))))

(deftest octave-a-false-check-is-a-result-not-an-error
  ;; Same distinction the Prolog engine draws: "your claim is false" and "your
  ;; code is broken" are different messages to send back to a model.
  (with-octave
    (fn [s]
      (let [r (octave/check s "2 > 3")]
        (is (:ok r) "a false claim evaluated successfully")
        (is (false? (:verdict r))))
      (is (not (:ok (octave/check s "chol([1 2; 2 1])")))
          "a genuine error is not a false verdict"))))
