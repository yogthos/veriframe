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
            [veriframe.engine.lean-repl :as lean-repl]
            [veriframe.engine.lean-search :as lean-search]
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
  ;;
  ;; And counted by exec'ing pgrep DIRECTLY rather than through `sh -c "pgrep
  ;; … | wc -l"`. The shell's own command line contains the pattern, so it
  ;; matched itself: with nothing running at all that pipeline reports 1, and
  ;; the figure moves with how sh forks. CI failed on before=2 after=1 — a
  ;; count that went DOWN, which no leak can cause. pgrep excludes itself, so
  ;; exec'd directly the only matches are real `sleep 45` processes and both
  ;; counts are 0.
  ;; The duration is unique to this invocation, so two concurrent runs of the
  ;; suite cannot see each other's sleeps. A machine-wide count of a fixed
  ;; `sleep 45` reported before=2 after=1 once in CI and again locally — both
  ;; times a straggler from another run, never a leak. (Pinning to our own pid
  ;; would be tidier, but java.lang.ProcessHandle is not available here.)
  (let [marker (str "45." (mod (System/currentTimeMillis) 100000))
        count-sleeps #(let [r (proc/run {:timeout-ms 5000} "pgrep" "-f" (str "sleep " marker))]
                        (count (remove str/blank? (str/split-lines (str (:out r))))))
        before (count-sleeps)
        t0 (System/currentTimeMillis)
        _ (dotimes [_ 3] (proc/run {:timeout-ms 500} "sleep" marker))
        elapsed (- (System/currentTimeMillis) t0)]
    (Thread/sleep 1500)
    (let [after (count-sleeps)]
      (is (= before after)
          (str "three killed `sleep " marker "` processes leaked;"
               " before=" before " after=" after))
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

(deftest an-octave-error-says-what-expr-is-actually-for
  ;; Pure string mapping, so it runs whether or not octave is installed.
  ;;
  ;; gen-14 lost three turns in five to this. A branch building an LP check
  ;; wrote statements into `expr` and got Octave's own "invalid use of
  ;; statement list", then tried to define a helper inline and got
  ;; "'check_farkas' undefined". Neither says the thing the model needed to
  ;; know: `expr` is ONE expression evaluated against the workspace, and
  ;; everything that needs statements goes through octave_eval first, which is
  ;; what keeps them in the workspace at all. vf_check already explains itself
  ;; when handed a matrix; these two deserve the same.
  (testing "statements in expr name octave_eval as the place for them"
    (let [m (octave/explain-check-error "parse error:\n\n  invalid use of statement list")]
      (is (str/includes? m "octave_eval"))
      (is (re-find #"(?i)one expression" m))))
  (testing "an undefined name suggests it was never defined in the workspace"
    (let [m (octave/explain-check-error "'check_farkas' undefined")]
      (is (str/includes? m "octave_eval"))
      (is (str/includes? m "check_farkas")
          "naming it, so the branch knows which one is missing")))
  (testing "a vector says a sweep is a measurement, not a verdict to collapse"
    ;; Observed in run 0d0c3560: told to wrap its column of counts in all(...),
    ;; the branch wrote `size(states,1).^m == 2.^m`, which is true by
    ;; construction and never reads the sweep it had just run. The all/any
    ;; advice is right for a predicate over a matrix and wrong for a result.
    (let [m (octave/explain-check-error
             "the expression produced a 8x1 value, not a scalar; wrap it in all(...) or any(...) to say which you mean")]
      (is (str/includes? m "all(...)") "the original advice survives")
      (is (str/includes? m "measure"))
      (is (re-find #"(?i)true by construction|comparing a formula to itself" m))))
  (testing "anything else is passed through untouched"
    (is (= "the expression produced NaN, which is not a verdict"
           (octave/explain-check-error
            "the expression produced NaN, which is not a verdict")))))

(deftest octave-check-explains-a-statement-list-error
  ;; The same thing end to end, so the mapping is actually wired into `check`
  ;; and not merely available.
  (with-octave
    (fn [s]
      (let [r (octave/check s "x = 1; y = 2; x < y")]
        (is (false? (:ok r)))
        (is (str/includes? (:error r) "octave_eval")
            (str "unhelpful error: " (:error r)))))))

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

(deftest octave-measures-a-value-where-check-wants-a-verdict
  ;; vf-0of. `check` is the only way an Octave turn banked anything and it
  ;; takes a scalar logical, so a sweep that locates where recovery breaks —
  ;; the most valuable thing this engine produces — had nowhere to go. This
  ;; op returns the VALUE, and the value is the evidence.
  (with-octave
    (fn [s]
      (octave/eval-code! s "sigmas = [0.25 0.5 0.75]; rate = mean (sigmas);")
      (let [r (octave/measure s "rate")]
        (is (:ok r) (str "a scalar is a measurement: " (:error r)))
        (is (= 0.5 (:value r)))
        (is (str/includes? (str (:text r)) "0.5")
            "with a rendering the artifact can quote"))
      (testing "a short vector is a measurement too — a sweep is the point"
        (let [r (octave/measure s "sigmas")]
          (is (:ok r))
          (is (= [0.25 0.5 0.75] (:value r)))))
      (testing "a verdict is a perfectly good measurement"
        (is (= 1 (:value (octave/measure s "rate > 0")))))
      (testing "but a non-answer is still not one"
        (are [expr] (not (:ok (octave/measure s expr)))
          "[]"                ; empty measures nothing
          "0/0"               ; NaN is not a value
          "'a string'"        ; not numeric
          "nosuchvariable"    ; an error is not a measurement
          "zeros (40, 40)"))  ; a whole field is not a measurement, summarise it
      (testing "and the refusal of a big one says to summarise it"
        (is (re-find #"(?i)summaris|summariz|mean|max"
                     (str (:error (octave/measure s "zeros (40, 40)")))))))))

(deftest an-import-line-does-not-cost-a-turn
  ;; Snippets are elaborated against a session that already has Mathlib, so an
  ;; `import` inside one is illegal and Lean says only "invalid 'import'
  ;; command, it must be used in the beginning of the file" — which tells the
  ;; model nothing it can act on. It cost 19 turns across two runs and hit 7 of
  ;; gen-18's branches; B3 burned two of the six failures that culled it on
  ;; exactly this. The harness supplies the import, so strip it and run the
  ;; proof.
  (testing "imports are removed, the declaration survives"
    (let [snippet "import Mathlib\nimport Mathlib.Tactic\n\ntheorem t : 1 = 1 := by rfl"]
      (is (= "theorem t : 1 = 1 := by rfl" (str/trim (lint/strip-lean-imports snippet))))
      (is (:ok (lint/lint-lean snippet))
          "and the snippet is no longer rejected for having them")))
  (testing "a line that merely mentions import in a name or comment is left alone"
    (are [s] (= s (lint/strip-lean-imports s))
      "theorem important_lemma : 1 = 1 := by rfl"
      "-- import Mathlib is supplied by the harness\ntheorem t : 2 = 2 := by rfl"))
  (testing "open lines are untouched — those are legal against an existing env"
    (let [s "open Finset\ntheorem t : 1 = 1 := by rfl"]
      (is (= s (lint/strip-lean-imports s))))))

;; --- Mathlib search ---------------------------------------------------------

(deftest search-says-so-when-nothing-really-matches
  ;; vf-f5c. Ranking kept any declaration sharing ONE token with the query, and
  ;; render admitted failure only on a completely empty result set — which
  ;; against 215k declarations essentially never happens. So every query came
  ;; back looking like a hit.
  ;;
  ;; gen-25 spent its first 29 turns on 14 searches and 12 artifact fetches
  ;; with zero verification attempts across three branches. The query "finite
  ;; directed graph cycle balanced indegree outdegree equal subset" returned
  ;; `Finite.subset` — 2 of 9 terms — rendered exactly like a real match. A
  ;; branch cannot tell that from a hit, so it concludes the lemma exists and
  ;; searches again with different words.
  ;;
  ;; The names stay visible: a near-miss is occasionally the right lead. What
  ;; goes is the false confidence.
  (testing "a weak best match is reported as a miss, with the near misses kept"
    (let [out (lean-search/render [{:n "Finite.subset" :k "theorem" :score 1.9
                                    :overlap 2 :idf-frac 0.18}
                                   {:n "eventually_subset_of_finite" :k "lemma"
                                    :score 1.8 :overlap 2 :idf-frac 0.15}]
                                  "finite directed graph cycle balanced indegree outdegree equal subset")]
      (is (not (str/includes? out "Mathlib matches for"))
          "it must not present noise the way it presents a hit")
      (is (re-find #"(?i)nothing in mathlib matched" out))
      (is (re-find #"shares 2 of 9 terms" out)
          "and says how weak the overlap actually is")
      (is (str/includes? out "Finite.subset")
          "the near misses are still shown")
      (is (re-find #"(?i)prov(e|ing) it (directly|yourself)|may not have" out)
          "and the branch is told the useful thing: stop searching, prove it")))

  (testing "a strong match is still reported as a match"
    (let [out (lean-search/render [{:n "Finset.sum_sub_distrib" :k "theorem"
                                    :score 2.9 :overlap 3 :idf-frac 0.95}]
                                  "Finset sum sub")]
      (is (str/includes? out "Mathlib matches"))
      (is (str/includes? out "Finset.sum_sub_distrib"))))

  (testing "an empty result set keeps its own advice"
    (is (re-find #"(?i)vocabulary" (lean-search/render [] "zzz"))))

  (testing "relevance is a share of the query's INFORMATION, not of its tokens"
    ;; Counting tokens equally made a match on `iff` worth a match on
    ;; `transgen`. In the 215,781-declaration index `iff` appears in 17,610
    ;; names and `transgen` in 30, so they are about 600x apart in how much
    ;; they narrow anything. gen-27 asked for "Relation.TransGen iff exists
    ;; list Chain'" and got mem_closure_iff_exists_list — three shared tokens,
    ;; every one of them a Mathlib name-particle, clearing a token-count floor
    ;; while missing the entire point of the query.
    (is (lean-search/relevant? {:idf-frac 0.9}))
    (is (not (lean-search/relevant? {:idf-frac 0.1})))
    (is (not (lean-search/relevant? {})) "no information matched is not a match"))

  (testing "IDF is computed from the index, so it needs no stoplist"
    (let [df {"iff" 17610 "exists" 3960 "list" 331 "transgen" 30 "acyclic" 10}
          n 215781
          idf (fn [t] (lean-search/idf df n t))]
      (is (> (idf "transgen") (idf "list")))
      (is (> (idf "list") (idf "exists")))
      (is (> (idf "exists") (idf "iff")))
      (is (> (idf "acyclic") (idf "transgen")))
      (is (> (idf "zzz-unseen") (idf "acyclic"))
          "a token absent from Mathlib is maximally discriminating"))))

;; --- Lean proof state -------------------------------------------------------

(deftest a-reply-without-goals-is-not-a-closed-proof
  ;; vf-4tw. closed? was (and (empty? errs) (empty? (:goals r))), and
  ;; send-command returns the REPL's JSON verbatim — so a reply carrying no
  ;; `goals` key at all gave (empty? nil), which is true, and the proof was
  ;; declared CLOSED. A missing field was read as an affirmative "no goals
  ;; remain".
  ;;
  ;; Three artifacts were confirmed this way on the single tactic `classical`,
  ;; which adds a decidability instance and closes nothing: gen-24 a#758 and
  ;; a#759 — lemma (B), reported as proved twice independently — and gen-25
  ;; a#780, TARGET 1, the last gap in the correctness chain. Both runs seeded
  ;; forward, so the void results propagated as inherited CONFIRMED lemmas.
  ;;
  ;; This file already draws the same distinction for warnings: treating them
  ;; like errors "is how `sorry` gets recorded as verified". Absence is not
  ;; assent.
  (let [reply (fn [m] (with-redefs [lean-repl/send-command (fn [& _] (assoc m :ok true))]
                        (lean-repl/apply-tactic {:id "s"} "classical" 1)))]

    (testing "no goals key at all — the harness cannot tell, so it must not claim"
      (let [r (reply {:proofState 2})]
        (is (not (:closed? r))
            "a proof is closed only when the REPL says the goal list is empty")
        (is (not (:ok r)) "and the turn does not read as a success")))

    (testing "when the REPL says WHY, the branch is told"
      ;; The reply shape that actually occurs: keys [:message :ok]. The REPL is
      ;; returning an error and explaining it, and the first version of this
      ;; check recorded the reply's KEYS while discarding the message — the
      ;; half that matters. gen-27 hit it 19 times and every branch saw only
      ;; "the harness cannot tell whether the tactic closed the proof".
      (let [r (reply {:message "Unknown proof state 7"})]
        (is (not (:closed? r)))
        (is (not (:ok r)))
        (is (some #(re-find #"Unknown proof state 7" (str (:data %))) (:errors r))
            "the REPL's own words reach the branch")))

    (testing "an affirmatively empty goal list IS closed"
      (let [r (reply {:proofState 2 :goals []})]
        (is (:closed? r))
        (is (:ok r))))

    (testing "goals remaining is neither closed nor failed"
      (let [r (reply {:proofState 2 :goals ["⊢ True"]})]
        (is (not (:closed? r)))
        (is (:ok r))
        (is (= ["⊢ True"] (:goals r)))))

    (testing "an error is a failed tactic even if goals are absent"
      (let [r (reply {:proofState 2
                      :messages [{:severity "error" :data "unknown tactic"}]})]
        (is (not (:closed? r)))
        (is (not (:ok r)))))

    (testing "a warning is not an error, and does not close anything either"
      (let [r (reply {:proofState 2 :goals ["⊢ True"]
                      :messages [{:severity "warning" :data "declaration uses sorry"}]})]
        (is (:ok r))
        (is (not (:closed? r)))))))

;; --- an artifact has to be citable ------------------------------------------

(deftest an-anonymous-example-is-refused
  ;; verify_lean banks whatever it proves as a confirmed artifact, and
  ;; seed-from-run! carries that code into later generations "so a branch can
  ;; re-confirm an inherited lemma in one cheap turn instead of reconstructing
  ;; the encoding". An `example` has no name, so no later proof can `exact` it,
  ;; `apply` it, or cite it at all — the only way to use it is to prove it
  ;; again, which is exactly what seeding exists to avoid.
  ;;
  ;; 25 confirmed Lean artifacts across the campaign are anonymous examples.
  ;; gen-27 a#801 is one: a correct two-line proof of [a,c].Chain' r iff r a c,
  ;; banked and uncitable.
  (testing "an example is rejected, with the fix named"
    (let [{:keys [ok warnings]} (lint/lint-lean "example (a : Nat) : a = a := rfl")]
      (is (not ok))
      (is (some #(re-find #"(?i)name" %) warnings))
      (is (some #(re-find #"theorem|lemma" %) warnings)
          "and says what to write instead")))

  (testing "a named declaration passes"
    (are [snippet] (:ok (lint/lint-lean snippet))
      "theorem foo (a : Nat) : a = a := rfl"
      "lemma bar (a : Nat) : a = a := rfl"
      "def baz : Nat := 1"))

  (testing "an example alongside a named declaration is fine"
    ;; The named one is what gets cited; a scratch example beside it is the
    ;; author's business.
    (is (:ok (lint/lint-lean "theorem foo (a : Nat) : a = a := rfl\nexample : True := trivial"))))

  (testing "the sorry check still fires, and first"
    (is (not (:ok (lint/lint-lean "theorem foo : True := by sorry"))))))

(deftest an-error-reply-is-not-a-successful-elaboration
  ;; The twin of vf-4tw, in the other REPL entry point, and worse. run-command
  ;; computed :ok as (empty? errs) with errs drawn from (:messages r), so a
  ;; reply carrying no :messages key gave errs = [] and :ok = true.
  ;;
  ;; That reply shape occurs: the REPL answers {:message ... :ok ...} when it
  ;; rejects a request, and apply-tactic hit it 19 times in gen-27 alone.
  ;; verify_lean branches on (:ok r) — so with :ok true and no sorries it runs
  ;; the faithfulness judge and, on a pass, banks a CONFIRMED artifact. A REPL
  ;; error laundered into a verified result, which is exactly how the three
  ;; `classical` artifacts happened, by the other door.
  ;;
  ;; Absence is not assent: a successful elaboration reply carries :env.
  (let [reply (fn [m] (with-redefs [lean-repl/send-command (fn [& _] (assoc m :ok true))]
                        (lean-repl/run-command {:id "s"} "theorem t : True := trivial" 1)))]

    (testing "an error reply with no messages key is not a success"
      (let [r (reply {:message "Unknown environment 3"})]
        (is (not (:ok r)))
        (is (some #(re-find #"Unknown environment 3" (str (:data %))) (:errors r))
            "and the REPL's own words reach the caller")))

    (testing "a clean elaboration is still a success"
      (let [r (reply {:env 2 :messages []})]
        (is (:ok r))
        (is (= 2 (:env r)))))

    (testing "a clean elaboration with only warnings is still a success"
      (is (:ok (reply {:env 2 :messages [{:severity "warning" :data "unused variable"}]}))))

    (testing "a genuine error is still a failure"
      (is (not (:ok (reply {:env 2 :messages [{:severity "error" :data "type mismatch"}]})))))

    (testing "sorries still come through"
      (is (seq (:sorries (reply {:env 2 :messages [] :sorries [{:goal "True"}]})))))))


(deftest apply-tactic-reports-the-sorries-the-repl-saw
  ;; `sorry` discharges a goal, so a step using one comes back with an empty
  ;; goal list and no errors — Lean warns and nothing else. The REPL does say
  ;; so, in `sorries`, and apply-tactic was dropping the field on the floor,
  ;; leaving proof_step nothing to check. gen-30 a#829 was banked confirmed on
  ;; that path with a `sorry` in its body.
  (let [reply (fn [m] (with-redefs [lean-repl/send-command (fn [& _] (assoc m :ok true))]
                        (lean-repl/apply-tactic {:id "s"} "exact sorry" 1)))]

    (testing "reported sorries are carried out"
      (let [r (reply {:proofState 2 :goals []
                      :sorries [{:goal "True" :proofState 3}]})]
        (is (= 1 (count (:sorries r))))
        (is (not (:closed? r))
            "and a goal discharged by sorry is not a closed proof")))

    (testing "an ordinary close is unaffected"
      (let [r (reply {:proofState 2 :goals []})]
        (is (empty? (:sorries r)))
        (is (:closed? r))))))

(deftest a-comment-that-merely-mentions-a-keyword-is-not-a-buried-declaration
  ;; gen-30 B1.3 wrote a complete, correct theorem with this comment above it:
  ;;
  ;;   -- guarded probes; a failed #check aborts only its own example, but
  ;;      these are separate text regions so I will use short examples instead.
  ;;
  ;; The lint counts declaration tokens before and after stripping comments and
  ;; warns when the count drops, so the word "example" in ordinary prose read
  ;; as a declaration hidden in a comment. The snippet was REJECTED and Lean
  ;; never ran — "nothing was run" is the message — costing the turn outright.
  ;; 12 turns across seven runs died this way.
  ;;
  ;; The real failure it guards is a declaration commented out by accident, and
  ;; that has a shape: a keyword followed by a name and a colon, or `example`
  ;; followed by a binder. Prose does not.
  (testing "prose mentioning a keyword does not block a real theorem"
    (let [snippet (str "theorem probe (x : Nat) : True := by\n"
                       "  -- a failed #check aborts only its own example, so I will\n"
                       "  -- use short examples instead; this theorem says nothing.\n"
                       "  trivial")]
      (is (:ok (lint/lint-lean snippet))
          (str "warnings: " (pr-str (:warnings (lint/lint-lean snippet)))))))

  (testing "an actually commented-out declaration is still caught"
    (let [snippet (str "-- theorem hidden (x : Nat) : x = x := by rfl\n"
                       "theorem real (x : Nat) : x = x := by rfl")]
      (is (not (:ok (lint/lint-lean snippet))))
      (is (str/includes? (str/join " " (:warnings (lint/lint-lean snippet)))
                         "line comment"))))

  (testing "a commented-out example is caught by its binder"
    (let [snippet (str "/- example (a : Nat) : a = a := rfl -/\n"
                       "theorem real (x : Nat) : x = x := by rfl")]
      (is (not (:ok (lint/lint-lean snippet))))))

  (testing "the checks that matter are untouched"
    (is (not (:ok (lint/lint-lean "-- theorem t : True := by trivial")))
        "everything inside comments is still nothing to check")
    (is (not (:ok (lint/lint-lean "theorem t : True := by sorry")))
        "and sorry is still refused")))
