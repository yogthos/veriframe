;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.engine.smt-templates
  "Vetted SMT-LIB assemblies for known problem shapes.

  Every false positive this harness has shipped came from the model writing
  its own SMT-LIB and getting it subtly wrong: a forall ordering chain that
  misses most pairs, ellipsis shorthand Z3 errors on, missing distinctness on
  the underlying constants. A template removes that class of bug for a shape
  we already understand, by doing the assembly in code that has tests.

  Each template assembles TWO encodings of opposite polarity, and a claim is
  confirmed only when both agree. That is the cross-check baked in: a bug in
  an existence encoding is unlikely to also appear in an enumeration one. It
  is also why `verify_template` needs no separate `review` call, while
  `verify_smt` does.

  The trade is flexibility. A genuinely novel problem still goes through
  verify_smt with the model's own encoding, and pays for the review."
  (:require [clojure.string :as str]))

(defn- validate-integer-set [elements]
  (cond
    (or (not (sequential? elements)) (empty? elements))
    {:ok false :error "`elements` must be a non-empty array of integers"}

    (not (every? int? elements))
    {:ok false :error (str "every element must be an integer; got "
                           (pr-str (first (remove int? elements))))}

    (not= (count (distinct elements)) (count elements))
    {:ok false :error (str "`elements` contains duplicates; the set must consist of"
                           " distinct integers")}

    :else {:ok true :values (vec elements)}))

(defn- check-set! [elements]
  (let [{:keys [ok values error]} (validate-integer-set elements)]
    (if ok values (throw (ex-info error {:elements elements})))))

(defn- positive-int! [label v]
  (if (and (int? v) (pos? v))
    v
    (throw (ex-info (str "`" label "` must be a positive integer, got " (pr-str v))
                    {label v}))))

(defn- in-s
  "A membership predicate over an explicit element list. Spelled out rather
  than quantified, which is the whole point."
  [var-name values]
  (str "(define-fun inS ((" var-name " Int)) Bool (or "
       (str/join " " (map #(str "(= " var-name " " % ")") values))
       "))"))

(defn- trivially-sat [comment]
  (str/join "\n" [(str "; " comment)
                  "(declare-const ok Bool)"
                  "(assert ok)"
                  "(check-sat)"]))

(defn- refuted [comments]
  (str/join "\n" (concat (take 10 comments) ["(assert false)" "(check-sat)"])))

;; --- sidon_set --------------------------------------------------------------

(defn- sidon-primary [{:keys [elements]}]
  (let [values (check-set! elements)
        n (count values)
        names (map #(str "a" %) (range n))]
    (str/join
     "\n"
     (concat
      (map #(str "(declare-const " % " Int)") names)
      (map (fn [nm v] (str "(assert (= " nm " " v "))")) names values)
      ;; Distinctness on the underlying constants. Without it Z3 can satisfy
      ;; pair-sum distinctness with an all-equal witness where every sum
      ;; collapses to one value, which is the size-23 false positive.
      [(str "(assert (distinct " (str/join " " names) "))")]
      [(str "(assert (distinct "
            (str/join " " (for [i (range n), j (range i n)]
                            (str "(+ a" i " a" j ")")))
            "))")]))))

(defn- sidon-cross [{:keys [elements]}]
  ;; `<=`, not `<`. A Sidon (B_2) set is one whose pairwise sums a+b for
  ;; a <= b are all distinct, and the i = j case carries real constraints:
  ;; {1,2,3,5} has 1+3 = 2+2 = 4 and is therefore NOT Sidon, while every sum
  ;; over strictly-distinct pairs is unique. The TypeScript original asserts
  ;; `(< a b)` here against a primary that enumerates i <= j, so the two
  ;; encodings answer different questions and disagree on exactly the sets
  ;; where the distinction bites. The cross-check caught it on the first set
  ;; tried, which is the argument for having one.
  (let [values (check-set! elements)]
    (str/join
     "\n"
     [(in-s "y" values)
      "(declare-const a Int) (declare-const b Int)"
      "(declare-const c Int) (declare-const d Int)"
      "(assert (inS a)) (assert (inS b)) (assert (inS c)) (assert (inS d))"
      "(assert (<= a b))"
      "(assert (<= c d))"
      "(assert (or (< a c) (and (= a c) (not (= b d)))))"
      "(assert (= (+ a b) (+ c d)))"
      "(check-sat)"])))

;; --- no_3ap_subset ----------------------------------------------------------

(defn- no-3ap-primary [{:keys [elements]}]
  (let [values (check-set! elements)]
    (str/join
     "\n"
     [(in-s "y" values)
      "(declare-const a Int) (declare-const d Int)"
      "(assert (> d 0))"
      "(assert (inS a))"
      "(assert (inS (+ a d)))"
      "(assert (inS (+ a (* 2 d))))"
      "(check-sat)"])))

(defn- no-3ap-cross [{:keys [elements]}]
  (let [v (check-set! elements)
        n (count v)
        hits (for [i (range n), j (range (inc i) n), k (range (inc j) n)
                   :when (= (- (v k) (v j)) (- (v j) (v i)))]
               (str "; 3-AP at S[" i "]=" (v i) ", S[" j "]=" (v j)
                    ", S[" k "]=" (v k)))]
    (if (seq hits)
      (refuted hits)
      (trivially-sat "cross-check enumerated all triples; no 3-AP found"))))

;; --- cap_set_f3n ------------------------------------------------------------

(defn- pow3 [i] (long (Math/pow 3 i)))

(defn- digits [n x]
  (mapv #(mod (quot x (pow3 %)) 3) (range n)))

(defn- from-digits [ds]
  (reduce + (map-indexed (fn [i d] (* d (pow3 i))) ds)))

(defn- cap-set-primary [{:keys [n elements]}]
  (let [n (positive-int! "n" n)
        values (check-set! elements)
        maxv (dec (pow3 n))]
    (doseq [x values]
      (when (or (neg? x) (> x maxv))
        (throw (ex-info (str "element " x " is outside [0, " maxv "] for F_3^" n)
                        {:element x :n n}))))
    (str/join
     "\n"
     (concat
      [(in-s "v" values)
       "(declare-const X Int)"
       "(declare-const Y Int)"
       "(declare-const Z Int)"
       "(assert (inS X)) (assert (inS Y)) (assert (inS Z))"
       "(assert (distinct X Y Z))"]
      (for [i (range n) :let [p (pow3 i)]]
        (str "(assert (= 0 (mod (+ (mod (div X " p ") 3) (mod (div Y " p ") 3)"
             " (mod (div Z " p ") 3)) 3)))"))
      ["(check-sat)"]))))

(defn- cap-set-cross [{:keys [n elements]}]
  (let [n (positive-int! "n" n)
        values (check-set! elements)
        s (set values)
        hits (for [[i a] (map-indexed vector values)
                   b (drop (inc i) values)
                   :let [da (digits n a)
                         db (digits n b)
                         dc (mapv (fn [x y] (mod (- 3 (mod (+ x y) 3)) 3)) da db)
                         c (from-digits dc)]
                   :when (and (contains? s c) (not= c a) (not= c b))]
               (str "; collision: a=" a "=(" (str/join "," da) "), b=" b
                    "=(" (str/join "," db) "), c=" c "=(" (str/join "," dc) ")"))]
    (if (seq hits)
      (refuted hits)
      (trivially-sat "cross-check enumerated all pairs; no 3-AP collision found"))))

;; --- schur_coloring ---------------------------------------------------------

(defn- validate-coloring [n k coloring]
  (let [n (positive-int! "n" n)
        k (positive-int! "k" k)]
    (when-not (and (sequential? coloring) (= n (count coloring)))
      (throw (ex-info (str "`coloring` must be an array of length " n "; got "
                           (if (sequential? coloring) (count coloring) (type coloring)))
                      {:n n})))
    (doseq [[i c] (map-indexed vector coloring)]
      (when-not (and (int? c) (<= 1 c k))
        (throw (ex-info (str "coloring[" i "] = " (pr-str c)
                             " is not an integer in [1, " k "]")
                        {:index i :value c}))))
    [n k (vec coloring)]))

(defn- schur-primary [{:keys [n k coloring]}]
  (let [[n _k colors] (validate-coloring n k coloring)
        ;; c(i) as a chain of ites, compact enough for Z3 at the n this
        ;; problem family reaches (S(5) = 160).
        body (reduce (fn [acc i] (str "(ite (= i " i ") " (colors (dec i)) " " acc ")"))
                     (str (colors (dec n)))
                     (range (dec n) 0 -1))]
    (str/join
     "\n"
     [(str "(define-fun c ((i Int)) Int " body ")")
      "(declare-const x Int)"
      "(declare-const y Int)"
      (str "(assert (and (>= x 1) (<= x " n ")))")
      (str "(assert (and (>= y 1) (<= y " n ")))")
      (str "(assert (<= (+ x y) " n "))")
      "(assert (= (c x) (c y)))"
      "(assert (= (c x) (c (+ x y))))"
      "(check-sat)"])))

(defn- schur-cross [{:keys [n k coloring]}]
  (let [[n _k colors] (validate-coloring n (or k (apply max coloring)) coloring)
        hits (for [x (range 1 (inc n))
                   y (range x (inc n))
                   :let [z (+ x y)]
                   :while (<= z n)
                   :when (= (colors (dec x)) (colors (dec y)) (colors (dec z)))]
               (str "; collision: x=" x ", y=" y ", x+y=" z
                    " all colored " (colors (dec x))))]
    (if (seq hits)
      (refuted hits)
      (trivially-sat "cross-check enumerated all triples; none monochromatic"))))

;; --- registry ---------------------------------------------------------------

(def templates
  {"sidon_set"
   {:name "sidon_set"
    :description (str "Verify that a candidate set S is a Sidon set (all pairwise sums"
                      " distinct). Runs a (distinct ...) primary AND an"
                      " existence-of-collision cross-check; both must agree.")
    :slots {:elements "Array of distinct positive integers — the candidate Sidon set."}
    :primary sidon-primary
    :cross sidon-cross
    :primary-verdict :sat
    :cross-verdict :unsat}

   "no_3ap_subset"
   {:name "no_3ap_subset"
    :description (str "Verify that a candidate subset S contains no three-term"
                      " arithmetic progression. Existence-of-3AP primary AND"
                      " explicit-enumeration cross-check.")
    :slots {:elements "Array of distinct positive integers — the candidate set."}
    :primary no-3ap-primary
    :cross no-3ap-cross
    :primary-verdict :unsat
    :cross-verdict :sat}

   "cap_set_f3n"
   {:name "cap_set_f3n"
    :description (str "Verify that a candidate subset S of F_3^n is a cap set (no"
                      " three-term AP in the F_3 vector space).")
    :slots {:n "Dimension of the F_3 vector space (e.g. 7 for F_3^7)."
            :elements (str "Array of distinct integers in [0, 3^n - 1], each encoding"
                           " an F_3^n vector via v_0 + 3*v_1 + 9*v_2 + …")}
    :primary cap-set-primary
    :cross cap-set-cross
    :primary-verdict :unsat
    :cross-verdict :sat}

   "schur_coloring"
   {:name "schur_coloring"
    :description (str "Verify that a candidate k-coloring of [1, n] has no monochromatic"
                      " Schur triple (x + y = z, all the same color).")
    :slots {:n "Upper bound; the integers coloured are 1..n."
            :k "Number of colors (at least 1)."
            :coloring "Array of length n with entries in [1, k]; coloring[i-1] colors i."}
    :primary schur-primary
    :cross schur-cross
    :primary-verdict :unsat
    :cross-verdict :sat}})

(defn list-templates
  "The catalogue, rendered for the model."
  []
  (str/join
   "\n"
   (for [t (vals templates)
         line (concat [(str "  • " (:name t) " — " (:description t))]
                      (for [[slot doc] (:slots t)]
                        (str "      " (name slot) ": " doc)))]
     line)))
