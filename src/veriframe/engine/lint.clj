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

(defn lint-lean [snippet]
  (let [snippet (strip-lean-imports snippet)
        trimmed (str/trim (or snippet ""))]
    (if (str/blank? trimmed)
      (result ["Lean snippet is empty."])
      (let [stripped (strip-lean-comments snippet)
            ws (transient [])]
        (doseq [tok lean-decl-tokens]
          (let [re (re-pattern (str "\\b" tok "\\b"))
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
            ;; Observed in the Frankl run.
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
            (when (re-find #"\b(sorry|admit)\b" stripped)
              (conj! ws (str "Snippet contains `sorry` or `admit` — placeholder tactics"
                             " that compile but do NOT prove anything (Lean only emits a"
                             " warning). Replace them with real tactics, or split the work:"
                             " `lean_define` adds the goal as an axiom you can use"
                             " elsewhere, and `proof_start` develops the closed proof step"
                             " by step.")))))

        (result (persistent! ws))))))
