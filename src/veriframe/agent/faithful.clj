;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.faithful
  "Deterministic objections to an artifact, checked before the judge is asked.

  Every false artifact this harness has shipped was caught by a person reading
  the encoding. The LLM judge helps — it blocked three defective encodings in
  one run and pushed a branch to a correct one — but it passed the first
  instance of a defect it later caught, it can be satisfied by weakening the
  claim instead of fixing the formula, and it cannot count. Those are not
  tuning problems; a reviewer that reasons in prose is the wrong instrument
  for `does this file contain fifteen assertions`.

  So the cheap certain checks run first. Each one here fires on a shape that
  actually shipped a false result, each is arithmetic or syntax rather than
  judgement, and each costs nothing. They are deliberately narrow: a check
  that guesses is worse than no check, because a warning nobody believes is a
  warning everybody routes around.

  Same contract as engine.lint: {:ok bool :warnings [str]}, warnings phrased
  for the model and surfaced verbatim."
  (:require [clojure.string :as str]
            [veriframe.engine.lint :as lint]))

(defn- result [warnings]
  ;; Strip the nils BEFORE deciding :ok — the callers below pass a fixed-arity
  ;; vector with a nil per check that found nothing, and `(empty? [nil nil])`
  ;; is false, which would fail every clean artifact closed.
  (let [ws (vec (remove nil? warnings))]
    {:ok (empty? ws) :warnings ws}))

;; --- Prolog: constraints posted where they get undone -----------------------

(def ^:private constraint-tokens
  "clpfd forms whose whole purpose is to POST a constraint. `ins` and the
  reified comparisons are the ones that matter; plain `is` and `=` are
  ordinary Prolog and stay out of this."
  #{"#=" "#\\=" "#>=" "#=<" "#>" "#<" "scalar_product" "sum(" "all_different"
    "all_distinct" "tuples_in" "element("})

(def ^:private undoing-constructs
  "Contexts that run their goal and then undo it.

  findall/3 collects solutions by backtracking, so bindings and constraint
  posts inside it are gone when it completes. forall/2 is \\+ (Cond, \\+ Action)
  — a double negation, same story. \\+ itself likewise. A program that posts
  its real constraints inside one of these and then labels is labelling an
  unconstrained problem, and it succeeds instantly with empty witnesses.

  Demonstrated in swipl: `X in 0..1, findall(_, (X #>= 5), _), label([X])`
  succeeds with X=0, while posting `X #>= 5` directly fails as it should. And
  `Xs ins 0..1, forall(member(X,Xs), X #>= 1), label(Xs)` yields [0,0,0]
  against [1,1,1] for the direct form."
  ["findall" "forall" "aggregate_all" "\\+"])

(defn- balanced-scope
  "The text of the parenthesised argument list starting at `from`, or nil."
  [^String s from]
  (let [n (count s)]
    (loop [i from, depth 0, started? false]
      (cond
        (>= i n) (when started? (subs s from (min n i)))
        :else
        (let [c (.charAt s i)]
          (cond
            (= c \() (recur (inc i) (inc depth) true)
            (= c \)) (if (= depth 1)
                       (subs s from (inc i))
                       (recur (inc i) (dec depth) started?))
            :else (recur (inc i) depth started?)))))))

(defn- occurrences
  "Every index at which `needle` starts in `s`."
  [^String s ^String needle]
  (loop [from 0, acc []]
    (if-let [i (str/index-of s needle from)]
      (recur (inc i) (conj acc i))
      acc)))

(defn posting-predicates
  "Names of predicates defined in `code` whose own body posts a constraint.

  a#338 did not post its constraints inline — it called `class_ok_reif`, which
  posted them. Looking only for constraint operators inside the undoing scope
  therefore sees nothing. Since a prolog artifact now carries its whole assert
  log, the definitions are present and one level of indirection can be
  resolved by reading them."
  [s]
  (->> (str/split s #"\.\s")
       (keep (fn [clause]
               (when (some #(str/includes? clause %) constraint-tokens)
                 (second (re-find #"(?m)^\s*([a-z]\w*)\s*(?:\(|\s*:-)" clause)))))
       set))

(defn constraint-in-undoing-scope
  "The first [construct snippet] where a clpfd constraint is posted inside a
  context that discards it, or nil.

  Counts both constraints written inline and calls to predicates defined here
  that post them — one level of indirection, which is what the observed
  failure used."
  [code]
  (let [s (lint/strip-prolog-comments (or code ""))
        posting (posting-predicates s)
        posts? (fn [scope]
                 (or (some #(str/includes? scope %) constraint-tokens)
                     (some #(re-find (re-pattern (str "\\b"
                                                      (java.util.regex.Pattern/quote %)
                                                      "\\s*\\(")) scope)
                           posting)))]
    (some (fn [construct]
            (some (fn [i]
                    (let [scope (balanced-scope s (+ i (count construct)))]
                      (when (and scope (posts? scope))
                        [construct (str/trim (subs scope 0 (min 90 (count scope))))])))
                  (occurrences s construct)))
          undoing-constructs)))

;; --- the claim has to add up on its own terms -------------------------------

(defn- index-set-size
  "How many indices `{0,1,2}`, `{0,..,6}` or `{0..4}` names, or nil.

  Only sets starting at 0 count. An index family runs from zero; a set of
  moduli — `D={3,5,7,9,…,945}`, which appears in the very claims this check
  exists for — does not, and counting one as the other would fire the check on
  every claim of that shape."
  [body]
  (let [nums (map parse-long (re-seq #"\d+" (or body "")))]
    (when (and (seq nums) (zero? (first nums)))
      (if (str/includes? body "..")
        (inc (last nums))
        (count nums)))))

(defn index-family-sizes
  "Sizes of the index families a claim quantifies over, in order."
  [claim]
  (->> (re-seq #"(?i)for\s+(?:each\s+|every\s+|all\s+)?[A-Za-z]\w*\s*(?:in|=)\s*\{([^}]*)\}"
               (or claim ""))
       (keep (comp index-set-size second))))

(defn overcounted-claim
  "When the parts a claim enumerates already exceed the total it states.

  gen-12 shipped two artifacts saying \"all of the following 14 inequalities\"
  and then listing families indexed 0..2, 0..4 and 0..6 — fifteen. Both
  encodings had all fifteen, so nothing that compares claim to encoding can
  see it; the claim contradicts itself.

  Only the surplus direction. A claim may name families and then add
  constraints it never enumerates, so a shortfall proves nothing — but what is
  enumerated is always a subset of the whole, so parts exceeding the stated
  total is a certain contradiction."
  [claim stated]
  (let [sizes (index-family-sizes claim)
        total (reduce + 0 sizes)]
    (when (and stated (seq sizes) (> total stated))
      {:stated stated :enumerated total :sizes (vec sizes)})))

(declare stated-count)

(defn- claim-warning
  "The objection to a claim considered on its own, independent of any engine."
  [claim]
  (when-let [{:keys [stated enumerated sizes]} (overcounted-claim claim (stated-count claim))]
    (str "The claim says " stated " but the families it lists come to "
         enumerated " (" (str/join " + " sizes) "). Whatever the engine was"
         " asked, the claim disagrees with itself before anything ran, so no"
         " verdict on it means what it says. Fix the count or the families.")))

(defn check-claim
  "Deterministic objections to a claim's own wording, for any engine."
  [claim]
  (result [(claim-warning claim)]))

(defn check-prolog
  "Deterministic objections to a Prolog goal and the rules behind it."
  [claim code]
  (result
   [(claim-warning claim)
    (when-let [[construct snippet] (constraint-in-undoing-scope code)]
      (str "This program posts a constraint inside `" construct "`, which runs"
           " its goal in a separate context and undoes bindings and constraint"
           " posts when it completes — so those constraints are gone before"
           " labeling runs, and the goal succeeds having enforced nothing."
           " Found in: `" snippet "…`."
           " Post the constraints directly (maplist/2 or explicit recursion over"
           " the list) and keep " construct " for collecting or testing ground"
           " terms only."))]))

;; --- SMT: coefficients that correspond to no modulus ------------------------

(def ^:private min-coefficient
  "Below this, an integer literal is a bound, an index or a small constant
  rather than a scaled coefficient, and the divisibility test says nothing."
  1000)

(defn- literals [s]
  (map parse-long (re-seq #"\d{4,}" (or s ""))))

(defn coefficient-literals
  "Integers standing in coefficient position — multiplying a variable.

  Only these are of the form g*L/m. The two shapes these encodings use are
  `(* c x)` and `(ite (…) c 0)`. Bare addends and comparison right-hand sides
  are aggregates and thresholds: the sum of the g=1 contributions, or L minus
  it. Testing those for divisibility flags correct artifacts — it rejected a
  verified encoding on 546750000, which is a sum of four legitimate
  coefficients and belongs to no single modulus."
  [s]
  (let [s (or s "")]
    (map parse-long
         (concat (map second (re-seq #"\(\s*\*\s+(\d{4,})" s))
                 (map second (re-seq #"\)\s+(\d{4,})\s+0\s*\)" s))))))

(defn unexplained-coefficients
  "Coefficients that are not `g*L/m` for any integer modulus m and small g.

  In a layered-density encoding scaled by L, every coefficient is g*L/m for a
  modulus m and g = gcd(m, q). So g*L must be divisible by the coefficient.
  A number that divides no small multiple of L corresponds to no modulus and
  is a typo — gen-13 shipped 17361625 where 15L/2835 = 17364375.

  Only reported when the file establishes the pattern: most coefficients
  explicable, a few not. A file where nothing fits is some other kind of
  encoding and this check has no opinion on it."
  [smtlib]
  (let [ns- (distinct (filter #(and % (>= % min-coefficient))
                              (coefficient-literals smtlib)))
        all (filter #(and % (>= % min-coefficient)) (literals smtlib))]
    (when (and (>= (count ns-) 4) (seq all))
      ;; L comes from the whole file — the threshold is often the largest
      ;; number present and is not itself a coefficient.
      (let [L (apply max all)
            ;; `boolean`, not the bare `some`: group-by keys on the return
            ;; value, and `some` yields nil rather than false, so the
            ;; unexplained coefficients would land under a nil key and the
            ;; destructuring below would silently find nothing.
            explains? (fn [c] (boolean (some #(zero? (mod (* % L) c)) (range 1 121))))
            {good true bad false} (group-by explains? (remove #(= % L) ns-))]
        (when (and (seq bad)
                   (>= (count good) (* 3 (count bad))))
          {:lcm L :bad (vec (sort bad))})))))

;; --- claim and encoding must agree on how many things there are -------------

(defn stated-count
  "The number of constraints the CLAIM says it is about, or nil.

  Only two phrasings, both of which appeared verbatim in shipped artifacts:
  an explicit count (\"all 15 class inequalities\", \"the following 14
  inequalities\") and a range (\"c=0..14\", \"classes c = 0..104\"). Anything
  less explicit is not a commitment and is left alone."
  [claim]
  (let [s (or claim "")]
    (or (some-> (re-find #"(?i)(\d+)\s+(?:class\s+)?(?:inequalit|constraint|classes)" s)
                second parse-long)
        (some-> (re-find #"(?i)c\s*=\s*0\s*\.\.\s*,?\s*(\d+)" s)
                second parse-long inc)
        (some-> (re-find #"(?i)classes?\s+c\s*=\s*0\s*(?:\.\.|to)\s*(\d+)" s)
                second parse-long inc))))

(defn- assertion-count [smtlib]
  (count (re-seq #"\(\s*assert\b" (lint/strip-smt-comments (or smtlib "")))))

;; --- SMT: an unsat that only searched part of the space ---------------------

(defn- declared-constants [smtlib]
  (set (map second (re-seq #"\(\s*declare-(?:const|fun)\s+([^\s()]+)" (or smtlib "")))))

(defn- pinned-constants [smtlib]
  (set (map second (re-seq #"\(\s*=\s+([A-Za-z_][\w-]*)\s+-?\d" (or smtlib "")))))

(defn partially-pinned-family
  "A family of declared constants of which some but not all are pinned to a
  literal, or nil.

  An unsat is only worth what the space it searched. `(assert (= y0 0))`
  removes every assignment with y0 ≠ 0, so unsat afterwards says nothing about
  those — and whether the pin is a sound symmetry break is a mathematical
  argument that lives outside the file. gen-13 recorded a refutation of \"the
  mod-15 condition for P_3000 is satisfiable\" from exactly that encoding.

  The tell is PART of a family. `(assert (= L 3281866875))` names a constant
  and excludes nothing; L is a family of one. Every member of a family pinned
  is a ground check of one assignment, which is a legitimate thing to ask.
  Only a proper subset reads as a restriction."
  [smtlib]
  (let [declared (declared-constants smtlib)
        pinned (pinned-constants smtlib)]
    (->> (group-by #(str/replace % #"\d+$" "") declared)
         (sort-by key)
         (keep (fn [[fam members]]
                 (let [p (sort (filter pinned members))]
                   (when (and (>= (count members) 3)
                              (seq p)
                              (< (count p) (count members)))
                     {:family fam :pinned (vec p) :size (count members)}))))
         first)))

(defn undisclosed-pins
  "A partial family pin the claim never mentions, or nil.

  Three artifacts from one run pin y0 out of y0..y20. Two of them say so —
  \"fixes y0 = 0 by the class-permutation symmetry\" — and one does not, and
  the one that does not is the one whose unsat did not hold up when the pin
  was removed by hand. That is the whole distinction this check can draw.

  Whether a disclosed symmetry reduction is SOUND is a mathematical argument
  about the problem, not a fact about the file, so it belongs to the reviewer
  that reads both — objecting to it here would be guessing, and a check that
  guesses gets routed around. What is decidable is whether the encoding
  narrowed the space without saying so."
  [claim smtlib]
  (when-let [{:keys [pinned] :as fam} (partially-pinned-family smtlib)]
    (let [mentions? (fn [v] (re-find (re-pattern (str "(?i)\\b"
                                                      (java.util.regex.Pattern/quote v)
                                                      "\\b"))
                                     (or claim "")))
          silent (remove mentions? pinned)]
      (when (seq silent)
        (assoc fam :undisclosed (vec silent))))))

(defn check-smt
  "Deterministic objections to an SMT-LIB encoding offered for a claim.

  `verdict` is what Z3 answered. It matters because a pinned variable can only
  damage an unsat — a SAT verdict hands back a real assignment, and one found
  inside a restricted space is still one."
  ([claim smtlib] (check-smt claim smtlib nil))
  ([claim smtlib {:keys [verdict]}]
   (let [stated (stated-count claim)
         asserts (assertion-count smtlib)]
     (result
      [(claim-warning claim)
       (when (= :unsat (some-> verdict name keyword))
         (when-let [{:keys [family size undisclosed]} (undisclosed-pins claim smtlib)]
           (str "This encoding pins " (str/join ", " undisclosed) " to a literal"
                " while leaving the other " (- size (count undisclosed))
                " member(s) of the `" family "` family free, and the claim never"
                " says so. Z3's unsat is therefore only about the slice where"
                " those hold, and the claim is about all of them. Either drop the"
                " pin and re-run, or state in the claim which symmetry reduction"
                " you are applying so it can be reviewed with the encoding.")))
       (when-let [{:keys [lcm bad]} (unexplained-coefficients smtlib)]
         (str "Coefficient(s) " (str/join ", " bad) " correspond to no modulus:"
              " with L = " lcm ", a layered coefficient is g*L/m for an integer"
              " modulus m, so g*L must divide evenly by it, and for these it"
              " does not for any g up to 120. Every other coefficient here fits"
              " that shape, so this is a typo rather than a different encoding."))
       ;; Only when the claim commits to a count AND the file is plainly short
       ;; of it. An encoding may carry extra asserts (bounds, symmetry breaks),
       ;; so more is unremarkable; fewer means constraints the claim promises
       ;; are simply absent.
       (when (and stated (pos? asserts) (< asserts stated))
         (str "The claim is about " stated " constraints but the encoding"
              " contains only " asserts " assertion(s). Whatever the engine"
              " answered, it was not asked about the other "
              (- stated asserts) ". Either assert them or state the claim the"
              " encoding actually checks — and if the count in the claim is"
              " simply wrong, fix the count."))]))))

;; --- Octave: a verdict that read nothing the engine computed ----------------

(def ^:private octave-builtin-operands
  "Names that are constants of the language rather than anything a workspace
  computed. `true && 1 > 0` reads no more than `1 > 0` does."
  #{"pi" "e" "Inf" "inf" "NaN" "nan" "NA" "eps" "true" "false" "realmax" "realmin"})

(defn reads-workspace?
  "Whether the checked expression has any operand the workspace supplied.

  Numeric literals go first so that the `e` in `1e-9` is not read as a name,
  then function names — an identifier immediately followed by `(` — because
  the name of a function is not an operand."
  [expr]
  (let [without-numbers (str/replace (or expr "") #"\d+(?:\.\d+)?(?:[eEdD][+-]?\d+)?" " ")
        without-calls (str/replace without-numbers #"[A-Za-z_]\w*\s*\(" "(")]
    (boolean (some (complement octave-builtin-operands)
                   (re-seq #"[A-Za-z_]\w*" without-calls)))))

(defn computes-nothing?
  "Whether the expression only compares constants the model wrote down.

  Deliberately narrower than \"reads no variable\". Using Octave as a
  calculator over literals is legitimate and the campaign did it repeatedly —
  `(45045 - 32805 == 12240)`, `(1/3+1/5+1/7+1/9+1/11+1/13+1/15+1/25) > 1` —
  and in those the engine really does the arithmetic being claimed. Firing on
  them would reject four correct artifacts to catch one bad one.

  What has no content is a comparison with no operation in it at all: no
  variable, no arithmetic, no call. `1.014488 > 1` is Octave confirming that
  the model can read its own notes."
  [expr]
  (let [s (or expr "")]
    (and (not (reads-workspace? s))
         (not (re-find #"[-+*/^\\]" (str/replace s #"^\s*-" "")))
         (not (re-find #"[A-Za-z_]\w*\s*\(" s)))))

(defn check-octave
  "Deterministic objections to an Octave check offered for a claim.

  Octave computes rather than decides, so what an artifact is worth depends
  entirely on the expression connecting the claim to the computation. gen-13
  recorded a confirmed artifact whose whole code was `1.014488 > 1`: the glpk
  solve behind that number ran on an earlier turn and appears nowhere, and the
  expression would have confirmed any claim it was attached to.

  Whether a computation that DID happen reaches the claim — a tolerance
  standing in for a strict inequality, a finite check offered for an infinite
  family — is judgement, and belongs to the reviewer that reads both."
  [claim expr]
  (result
   [(claim-warning claim)
    (when (computes-nothing? expr)
      (str "`" (str/trim (str expr)) "` compares constants: no variable, no"
           " arithmetic, no call. Octave computed nothing here, so the verdict"
           " restates numbers you already had and would have come out the same"
           " for any claim attached to it. Whatever produced them is not part of"
           " the artifact. Check the computed values themselves (`val > 1`, or"
           " `vf_approx(val, expected, tol)`), or recompute the quantity inside"
           " the expression."))]))

(defn check-lean
  "Deterministic objections to a Lean snippet offered for a claim.

  Lean checks its own proofs, so there is nothing here to add on the proof
  side — a snippet that elaborates without `sorry` proves its statement. What
  remains is whether the STATEMENT is the claim, which is the judge's job, and
  whether the claim is coherent at all, which is arithmetic."
  [claim _code]
  (result [(claim-warning claim)]))
