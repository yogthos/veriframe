;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.engine.lint
  "Pre-execution linters for SMT-LIB, Prolog, and Lean.

  Engines accept inputs that are valid by spec and functionally empty. The
  textbook case: an SMT-LIB body on one line with a mid-line `;` comment that
  swallows every assertion, after which Z3 returns SAT against a
  constraint-free formula and the harness reports it as verified. That is the
  n=500 Sidon false positive, and it is the reason these run BEFORE the engine
  rather than interpreting its output afterwards.

  Every linter returns {:ok bool :warnings [str]}. `:ok false` means at least
  one warning is severe enough to block execution. Warnings are phrased for
  the model and get surfaced verbatim.

  These are heuristics over comment-stripped text, not parsers. They are aimed
  at the specific false-positive shapes this harness has actually shipped."
  (:require [clojure.string :as str]))

(defn- result [warnings]
  {:ok (empty? warnings) :warnings (vec warnings)})

(defn- count-matches [re s]
  (count (re-seq re s)))

(defn- paren-delta [s]
  (reduce (fn [d c] (case c \( (inc d) \) (dec d) d)) 0 s))

;; --- SMT-LIB ----------------------------------------------------------------

(defn strip-smt-comments
  "SMT-LIB has only line comments: `;` to end of line."
  [s]
  (str/replace s #";[^\n]*" ""))

(defn- strip-string-literals [s]
  (str/replace s #"\"[^\"]*\"" "\"\""))

(def ^:private smt-decl-tokens
  ["assert" "declare-fun" "declare-const" "declare-sort" "define-fun"])

(defn lint-smt [smtlib]
  (let [trimmed (str/trim (or smtlib ""))]
    (if (str/blank? trimmed)
      (result ["SMT-LIB input is empty."])
      (let [stripped (strip-smt-comments smtlib)
            ws (transient [])]

        ;; The biggest real-world bug: a mid-line `;` ate one or more forms.
        ;; Counting before and after the strip is how you see it.
        (doseq [tok smt-decl-tokens]
          (let [re (re-pattern (str "\\(\\s*" tok "\\b"))
                before (count-matches re smtlib)
                after (count-matches re stripped)]
            (when (> before after)
              (conj! ws (str (- before after) " `" tok
                             "` form(s) appear inside a `;` line comment and will be"
                             " ignored by Z3. Put each statement on its own line, or end"
                             " the comment with a newline before further code.")))))

        (let [d (paren-delta stripped)]
          (when-not (zero? d)
            (conj! ws (str "Unbalanced parentheses after stripping comments (depth "
                           d "). Open and close counts don't match."))))

        (if (str/blank? (str/trim stripped))
          (conj! ws "All SMT-LIB content was inside comments; nothing to check.")
          (do
            (when-not (re-find #"\(\s*(assert|declare-|define-|check-sat|set-logic|set-option)\b"
                               stripped)
              (conj! ws (str "SMT-LIB body has no `(assert ...)`, `(declare-...)`, or"
                             " `(check-sat)` after stripping comments. Z3 would have"
                             " nothing to do.")))

            ;; Ellipsis shorthand. The model wrote `...` meaning "and so on";
            ;; Z3 emitted parse errors and then SAT'd the empty formula that
            ;; survived. SMT-LIB has no abbreviation syntax. String literals
            ;; are blanked first so a `...` inside one does not trip this.
            (when (str/includes? (strip-string-literals stripped) "...")
              (conj! ws (str "Literal `...` (ellipsis) detected outside string literals."
                             " SMT-LIB has no abbreviation syntax — every"
                             " (declare-const ...), (assert ...), and (+ a_i a_j) must be"
                             " spelled out explicitly. Z3 will emit parse errors and may"
                             " silently SAT the empty constraint set that survives.")))

            ;; Pair-sum distinctness with no distinctness on the underlying
            ;; constants: satisfiable by an all-equal witness where every sum
            ;; collapses to one value. This is the size-23 false positive.
            (when (and (re-find #"\(\s*distinct\s+\(\s*\+\s" stripped)
                       (not (re-find #"\(\s*distinct\s+[A-Za-z_]" stripped)))
              (conj! ws (str "`(distinct (+ a_i a_j) ...)` asserts pair-sum distinctness"
                             " but there's no sibling `(distinct a_1 a_2 ... a_n)`"
                             " constraining the underlying constants. Z3 can SAT this with"
                             " a degenerate witness (e.g. all-zero values) where pair-sums"
                             " collapse to a single element. Add"
                             " `(assert (distinct a_1 ... a_n))` so the constants must take"
                             " distinct values.")))

            ;; forall over what is really a small finite set. Ordering-chain
            ;; encodings (i <= j <= k <= l) miss most pair-vs-pair comparisons,
            ;; which is the size-26 false positive.
            (when (re-find #"\(\s*forall\s+\(\s*\([^)]+\s+Int\s*\)" stripped)
              (let [bounds (->> (re-seq #"<=\s+\w+\s+(\d+)\s*\)" stripped)
                                (map (comp parse-long second))
                                (filter #(and (pos? %) (< % 100))))]
                (when (seq bounds)
                  (conj! ws (str "`(forall ((i Int) ...) ...)` with a small finite range"
                                 " (max bound observed: " (apply max bounds) ")."
                                 " Universal quantification over Int is hard for Z3 to"
                                 " discharge correctly when the property is really \"for all"
                                 " members of a finite set\", and ordering-chain encodings"
                                 " (i <= j <= k <= l) routinely miss most cases. Enumerate"
                                 " the pairs explicitly with (distinct ...) instead.")))))))

        (result (persistent! ws))))))

;; --- Prolog -----------------------------------------------------------------

(defn strip-prolog-comments [s]
  (-> s
      (str/replace #"(?s)/\*.*?\*/" "")
      (str/replace #"%[^\n]*" "")))

(defn lint-prolog-program [code]
  (let [trimmed (str/trim (or code ""))]
    (cond
      (str/blank? trimmed)
      (result ["Prolog program is empty."])

      :else
      (let [stripped (str/trim (strip-prolog-comments code))]
        (cond
          (str/blank? stripped)
          (result ["All Prolog content was inside comments; nothing to load."])

          (not (str/includes? stripped "."))
          (result [(str "Prolog program contains no `.` clause terminators. Each fact,"
                        " rule, or directive must end with a period.")])

          :else (result []))))))

(defn normalize-query
  "Drop a leading `?-` and a trailing `.`; the session supplies both."
  [goal]
  (let [g (str/trim (or goal ""))
        g (if (str/starts-with? g "?-") (str/trim (subs g 2)) g)
        g (if (str/ends-with? g ".") (str/trim (subs g 0 (dec (count g)))) g)]
    g))

(defn lint-prolog-query [goal]
  (let [trimmed (str/trim (or goal ""))]
    (cond
      (str/blank? trimmed)
      (result ["Prolog query is empty."])

      :else
      (let [cleaned (normalize-query trimmed)]
        (cond
          (str/blank? cleaned)
          (result ["Prolog query is empty after stripping `?-` / trailing dot."])

          (str/blank? (str/trim (strip-prolog-comments cleaned)))
          (result ["Prolog query body is entirely commented out."])

          :else (result []))))))

;; --- Lean -------------------------------------------------------------------

(defn strip-lean-comments [s]
  (-> s
      (str/replace #"(?s)/-.*?-/" "")
      (str/replace #"--[^\n]*" "")))

(def ^:private lean-decl-tokens
  ["theorem" "example" "lemma" "def" "abbrev" "instance"])

(defn- decl-shaped-re
  "A pattern matching `tok` used as a DECLARATION, not merely mentioned.

  Counting bare tokens made any prose in a comment look like a buried
  declaration. gen-30 B1.3 lost a complete, correct theorem to the comment
  \"a failed #check aborts only its own example\" — `example` in a sentence —
  and the snippet was rejected without Lean running at all. Twelve turns
  across seven runs died that way.

  A Lean declaration has a shape: a keyword, then a name, then binders or a
  colon. `example` is the exception, being anonymous, so it is recognised by
  the binder or colon alone. Prose has neither — \"this theorem says nothing\"
  never reaches a colon, and \"its own example,\" is followed by punctuation."
  [tok]
  (if (= tok "example")
    (re-pattern (str "\\b" tok "\\b\\s*[({\\[:]"))
    (re-pattern (str "\\b" tok "\\b\\s+[A-Za-z_][A-Za-z0-9_'!?.]*[^\\n]*:"))))

(defn strip-lean-imports
  "Drop `import` lines from a Lean snippet.

  Snippets are elaborated against a session that already has Mathlib, so an
  import inside one is illegal and Lean says only \"invalid 'import' command,
  it must be used in the beginning of the file\" — true, unactionable, and
  identical every time. It cost 19 turns across gen-17 and gen-18 and reached
  seven of gen-18's branches; B3 spent two of the six failures that culled it
  on this alone. The harness supplies the import, so the snippet does not need
  one and the proof should just run.

  Only a line whose first token is `import` goes. `open` is left alone — it is
  legal against an existing environment — and so is any line that merely
  mentions the word."
  [snippet]
  (->> (str/split-lines (or snippet ""))
       (remove #(re-matches #"\s*import\s+\S.*" %))
       (str/join "\n")))

(defn vacuous-lean-statement?
  "Whether every declaration in the snippet concludes `True`.

  `True` is provable by `trivial` and says nothing, so a declaration ending in
  it substantiates no claim however honestly the proof runs. gen-30 a#832 was
  banked CONFIRMED as `theorem probe_top_print : True := by trivial` beside a
  `#print`, with a claim that described itself as \"a mechanical inspection …
  not a theorem\".

  The CONCLUSION, not the presence of the word: `(h : True)` as a hypothesis is
  ordinary, and `True ∧ False ∨ 1 = 1` concludes something real. So this looks
  only at the text between the last top-level `:` and the `:=`, which is where
  a Lean conclusion lives."
  [snippet]
  (let [decls (->> (str/split-lines (strip-lean-comments (or snippet "")))
                   (filter #(re-find #"\b(theorem|lemma|example)\b" %)))]
    (and (seq decls)
         (every? (fn [line]
                   (when-let [m (re-find #":\s*True\s*:=" line)]
                     (boolean m)))
                 decls))))

(defn lint-lean
  "Lint a Lean declaration before it reaches the engine.

  `sorry`/`admit` block by default because they compile with a warning,
  not an error — without the check a snippet that proves nothing is
  recorded as confirmed (observed in the Frankl run). `{:allow-sorry? true}`
  is sketch mode: same checks, opposite polarity on that one line, because
  a Draft-Sketch-Prove skeleton is required to HAVE them. Everything else —
  empty snippet, buried declarations, anonymous `example` — still fires,
  since a skeleton with no declaration in it is not a plan either."
  ([snippet] (lint-lean snippet nil))
  ([snippet {:keys [allow-sorry?]}]
   (let [snippet (strip-lean-imports snippet)
         trimmed (str/trim (or snippet ""))]
     (if (str/blank? trimmed)
       (result ["Lean snippet is empty."])
       (let [stripped (strip-lean-comments snippet)
             ws (transient [])]
         (doseq [tok lean-decl-tokens]
           (let [re (decl-shaped-re tok)
                 before (count-matches re snippet)
                 after (count-matches re stripped)]
             (when (> before after)
               (conj! ws (str (- before after) " `" tok
                              "` declaration(s) appear inside a `--` line comment or"
                              " `/-...-/` block and will be ignored by Lean. Put each"
                              " declaration on its own line.")))))

         (if (str/blank? (str/trim stripped))
           (conj! ws "All Lean content was inside comments; nothing to check.")
           (do
             (when-not (re-find #"\b(theorem|example|lemma|def|abbrev|instance|structure|class|inductive)\b"
                                stripped)
               (conj! ws (str "Lean snippet has no `theorem` / `example` / `lemma` / `def`"
                              " declaration after stripping comments. verify_lean expects a"
                              " complete declaration; for individual tactics use"
                              " `proof_step`.")))
             ;; sorry and admit compile with a warning, not an error, so without
             ;; this a snippet that proves nothing gets recorded as confirmed.
             ;; Observed in the Frankl run. In sketch mode the check is
             ;; inverted rather than dropped: the tool that calls it WANTS the
             ;; sorries, and the empty-sorry case is handled by the engine
             ;; result instead.
             ;; An `example` has no name, so nothing can cite it. verify_lean
             ;; banks whatever it proves, and seed-from-run! carries that code
             ;; into later generations "so a branch can re-confirm an inherited
             ;; lemma in one cheap turn instead of reconstructing the encoding"
             ;; — which an anonymous declaration makes impossible. 25 confirmed
             ;; artifacts across the campaign are examples: proved, banked, and
             ;; uncitable. Checked only when there is no named declaration
             ;; alongside, since a scratch example next to a real theorem is the
             ;; author's business.
             (when (and (re-find #"\bexample\b" stripped)
                        (not (re-find #"\b(theorem|lemma|def|abbrev|instance|structure|class|inductive)\b"
                                      stripped)))
               (conj! ws (str "This is an `example`, which has no name — nothing can cite"
                              " it, so a later proof (or a later run) cannot use what you"
                              " proved except by proving it again. Give it a name:"
                              " `theorem foo ...` or `lemma foo ...`. Results here are"
                              " inherited by later runs, and an anonymous one is inherited"
                              " as text nobody can apply.")))
             (when (and (not allow-sorry?)
                        (re-find #"\b(sorry|admit)\b" stripped))
               (conj! ws (str "Snippet contains `sorry` or `admit` — placeholder tactics"
                              " that compile but do NOT prove anything (Lean only emits a"
                              " warning). Replace them with real tactics, or split the work:"
                              " `sketch` banks a skeleton WITH its sorries as a plan, and"
                              " `proof_start`/`proof_step` develop the closed proof step by"
                              " step.")))
             ;; An axiom is `sorry` with no warning attached, and it is worse in
             ;; two ways: nothing in the output marks it, and it does not appear
             ;; in the hypotheses of the theorem it supports, so a reader of the
             ;; STATEMENT cannot tell. `axiom lemma_A : <the goal>` followed by
             ;; `theorem closed : <the goal> := lemma_A` would be banked as
             ;; confirmed.
             ;;
             ;; Not hypothetical: gen-31 B1.2 probed exactly this and the
             ;; harness accepted it (a#885). It was looking for a way to stand
             ;; in for inherited components it could not cheaply retype
             ;; (vf-vw4), which is the pressure that makes this attractive
             ;; precisely when the stakes are highest — assembling a final
             ;; composition.
             ;;
             ;; Refused in sketch mode too: a sketch's sanctioned placeholder is
             ;; `sorry`, which the engine can see and count.
             (when (re-find #"(?m)^\s*(@\[[^\]]*\]\s*)?axiom\b" stripped)
               (conj! ws (str "Snippet declares an `axiom`. An axiom is assumed, not"
                              " proved — it is `sorry` with no warning, and unlike a"
                              " hypothesis it does not appear in the statement of the"
                              " theorem that uses it, so nothing downstream can tell the"
                              " result rests on it. To build on an earlier result, restate"
                              " and reprove it in this snippet, or take it as an explicit"
                              " HYPOTHESIS of your theorem so the dependency is visible in"
                              " the statement.")))))

         (result (persistent! ws)))))))
