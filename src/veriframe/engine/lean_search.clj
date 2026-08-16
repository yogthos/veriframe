;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.engine.lean-search
  "Premise retrieval over Mathlib.

  The literature calls this mandatory for LLM theorem proving — Magnushammer,
  ReProver, LeanDojo all land on the same finding. Mathlib has on the order of
  200k named declarations and no model holds them. Without a way to search by
  meaning, a model either recalls a lemma name correctly or invents one.

  Lexical rather than dense, on purpose. Mathlib's naming convention is
  descriptive (`Real.sqrt_le_sqrt`, `Nat.add_comm`, `Finset.sum_pow`), and the
  index carries each declaration's statement as well as its name, so token
  overlap reaches both what a name spells and what the declaration actually
  claims. An embedding index would add a model dependency and a build step
  to buy recall this problem may not need. If keyword recall proves
  insufficient the index is the thing to replace, not the interface.

  The index is a plain text file and is never loaded into memory. PLAN.md
  flagged holding ~200k entries in a Chez process as a risk and named the
  fallback: search the cached file rather than an in-memory structure. It was
  the right call twice over — scanning the source in-process had not finished
  after thirty minutes, and even after grep did the extraction in three
  seconds, decoding the results into 215k maps and tokenizing them per query
  was itself the bottleneck. Both passes are now grep's job."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [veriframe.engine.proc :as proc]))

(def cache-path ".cache/mathlib-index.txt")

(def ^:private awk-extractor
  "Joins a declaration's continuation lines and emits `<kind> <name> ::
  <statement>` per declaration. Kept as a file rather than inlined: it is
  easier to run standalone against a Mathlib checkout than an awk program
  string escaped into a shell command."
  "tools/mathlib-index.awk")

(defn- mathlib-root [workspace]
  (io/file workspace ".lake" "packages" "mathlib" "Mathlib"))

(defn build-index!
  "Extract every declaration from the Mathlib source and cache it.

  Shelled out to awk rather than run in-process: extraction over Mathlib's
  7871 files took more than thirty minutes inside this runtime and 7.7s as a
  subprocess, and regex over hundreds of megabytes is not what this runtime
  is for. The extractor joins continuation lines, which is the whole point —
  43% of Mathlib declarations continue past their first line, so the previous
  single-line grep matched `<kind> <name>` and lost the statement for nearly
  half the library. Each line comes out as `<kind> <name> :: <statement>`.
  awk is POSIX and always a real binary; `rg` was rejected because it is
  often a shell function or absent, and a subprocess cannot call a shell
  function.

  Degrades to an empty index rather than throwing: premise search is an aid,
  and a branch should not lose its turn because the index is missing. A
  failed rebuild leaves any existing cache alone — stale beats gone."
  [{:keys [workspace]}]
  (let [root (mathlib-root workspace)]
    (if-not (.exists root)
      (do (log/warn "no Mathlib source at" root "— run tools/setup-lean.sh;"
                    "the premise index stays empty")
          cache-path)
      (do
        (log/info "building the Mathlib premise index")
        (let [{:keys [out exit timeout]}
              (proc/run {:timeout-ms 120000}
                        "sh" "-c"
                        (str "find '" (.getAbsolutePath root) "' -name '*.lean' -print0"
                             " | xargs -0 awk -f " awk-extractor))
              ok? (and (not timeout) (zero? (or exit 1)))
              text (when ok? (or out ""))]
          (if (str/blank? text)
            (log/warn "the Mathlib index came back empty;"
                      "keeping any existing cache")
            (do
              ;; Plain text, one declaration per line, NOT parsed into memory.
              ;; Decoding 230k maps and holding them was slower in this
              ;; runtime than the scan it replaced, and searching them meant
              ;; tokenizing all 230k per query.
              (io/make-parents cache-path)
              (spit cache-path text)
              (log/info "indexed" (count (str/split-lines text))
                        "declarations")))
          cache-path)))))

(defn- index-file
  "Where the premise index lives. `:mathlib-index` in the engine config
  overrides the default cache, so a test or an operator can point search at
  another corpus without rebuilding anything."
  [cfg]
  (let [f (io/file (or (:mathlib-index cfg) cache-path))]
    (when-not (.exists f) (build-index! cfg))
    (.getAbsolutePath f)))

(defn- parse-line
  "One index line into {:k kind :n name :s statement}.

  The format is detected PER LINE, because a long-running server can hold a
  cache written by an older build: the new format is `<kind> <name> ::
  <statement>`, the old one bare `<kind> <name>`. The old lines keep their
  modifiers (`protected theorem foo`), the new ones do not — awk strips them
  before printing. A declaration with an empty statement prints as `<kind>
  <name> ::`, 2,169 of 230,291 in the current Mathlib, so the no-statement
  path is not vestigial."
  [line]
  (let [line (str/trim line)]
    (if-let [[_ k n s] (re-matches #"(\S+)\s+(\S+)\s+::( (.*))?" line)]
      {:k k :n n :s (str/trim (or s ""))}
      (let [parts (str/split line #"\s+")
            ;; The declaration keyword, not the first token: `protected
            ;; theorem foo` and `noncomputable def bar` both lead with a
            ;; modifier.
            kind (or (some #{"theorem" "lemma" "def" "abbrev" "instance"
                             "structure" "inductive"}
                           parts)
                     (first parts))]
        {:k kind :n (last parts) :s nil}))))

(def ^:private synonyms
  "Description-to-Mathlib-vocabulary. Lexical search matches names, and Mathlib
  names use the operation rather than its English description: a query for
  \"commutativity of addition\" has to reach `add_comm`. This is a small hand
  table rather than a stemmer because the mapping is idiomatic, not
  morphological, and it is the cheapest thing that moves recall. If it grows
  past a screen, that is the signal to replace the index with embeddings."
  {"commutativity" ["comm"] "commutative" ["comm"] "commutes" ["comm"]
   "associativity" ["assoc"] "associative" ["assoc"]
   "distributivity" ["distrib"] "distributive" ["distrib"]
   "addition" ["add"] "adding" ["add"] "sum" ["sum" "add"]
   "multiplication" ["mul"] "product" ["prod" "mul"] "times" ["mul"]
   "subtraction" ["sub"] "division" ["div"] "quotient" ["div"]
   "power" ["pow"] "exponent" ["pow"] "square" ["sq"] "root" ["sqrt"]
   "monotone" ["mono"] "monotonic" ["mono"] "increasing" ["mono"]
   "inequality" ["le" "lt"] "less" ["lt" "le"] "greater" ["gt" "ge"]
   "injective" ["injective"] "surjective" ["surjective"]
   "continuous" ["continuous"] "derivative" ["deriv"] "integral" ["integral"]
   "cardinality" ["card"] "factorial" ["factorial"] "prime" ["prime"]
   "divides" ["dvd"] "divisible" ["dvd"] "modulo" ["mod"] "remainder" ["mod"]
   "natural" ["nat"] "integer" ["int"] "rational" ["rat"] "real" ["real"]
   "induction" ["induction" "rec"] "empty" ["empty"] "union" ["union"]
   "intersection" ["inter"] "subset" ["subset"] "member" ["mem"]})

(defn- tokens [s]
  (->> (str/split (str/lower-case (or s "")) #"[^a-z0-9]+")
       (remove str/blank?)
       (remove #{"the" "a" "an" "of" "for" "and" "or" "is" "that" "with" "to"
                 "lemma" "theorem" "prove" "show" "about" "all" "any" "over"})
       (mapcat (fn [t] (concat [t] (get synonyms t))))
       (filter #(>= (count %) 2))
       distinct
       vec))

(defn idf
  "Inverse document frequency of a token: how much matching it narrows things.

  log(N / (1 + df)). A token absent from Mathlib scores highest, which is
  right — a query term that appears nowhere is either the caller's own coinage
  or a misspelling, and either way a name containing it is unusually
  interesting."
  [df-map n t]
  (Math/log (/ (double n) (inc (double (get df-map t 0))))))

(def ^:private df-cache
  "Token -> how many index lines contain it, plus the line count.

  Counted over the name AND the statement, because idf's job here is to say
  how much a token narrows grep's candidate set, and that set is lines, not
  names: with statements in the index a token sitting in 20k statements is a
  weak signal even where it sits in only 100 names. The index is already on
  disk for grep, so this needs no new artefact and stays correct as Mathlib
  moves — the alternative, a hand-curated stoplist of common particles, would
  need maintaining and would still be a guess.

  Keyed by index path and memoised for the process: the tests point search
  at throwaway indexes, and a process-wide memo would answer one corpus's
  queries with another corpus's counts."
  (atom nil))

(defn- doc-freq
  [cfg]
  (let [path (index-file cfg)]
    (if (and @df-cache (= path (:path @df-cache)))
      @df-cache
      (let [f (io/file path)
            {:keys [df n]}
            (if-not (.exists f)
              {:df {} :n 0}
              (with-open [r (io/reader f)]
                (reduce (fn [{:keys [df n]} line]
                          (let [{name :n stmt :s} (parse-line line)]
                            (if (str/blank? name)
                              {:df df :n n}
                              {:n (inc n)
                               :df (reduce (fn [m t] (update m t (fnil inc 0)))
                                           df
                                           (tokens (str name " " (or stmt ""))))})))
                        {:df {} :n 0}
                        (line-seq r))))
            cached {:path path :df df :n (max n 1)}]
        (reset! df-cache cached)))))

(def ^:private statement-weight
  "How much a query term matching the STATEMENT counts against one matching
  the NAME: a quarter.

  Names are curated by Mathlib's naming convention — a query's words land in
  `Real.sqrt_le_sqrt` because somebody named it that. Statements are written
  in binders full of structural noise (`Type`, `inst`, `α`, `hf`, `→`) that
  shares spelling with an enormous fraction of the library without sharing
  any meaning with the query. So a statement hit is evidence, but weaker
  evidence than a name hit, and 4:1 is the ratio that held up on the
  campaign replay (see relevance-floor below for the numbers): low enough
  that a name match beats a statement match on idf-equal terms, high enough
  that a declaration whose statement says `Continuous` surfaces for a
  continuity query its name never spells."
  0.25)

(defn search
  "Top-k Mathlib declarations matching `query`.

  grep narrows the index to the lines that contain any query token, and only
  those are ranked in-process. Both fields of a line are scored — the name at
  full weight, the statement at statement-weight — because the name is where
  Mathlib puts the meaning and the statement is where it puts the proof
  obligations. A stale bare-name line simply has no statement to score."
  ([cfg query] (search cfg query 10))
  ([cfg query k]
   (let [qt (tokens query)
         {dfm :df dn :n} (doc-freq cfg)
         qidf (reduce + 0.0 (map #(idf dfm dn %) qt))]
     (if (empty? qt)
       []
       (let [pattern (str/join "|" qt)
             {:keys [out exit]} (proc/run {:timeout-ms 30000}
                                          "grep" "-ihE" pattern (index-file cfg))]
         (if-not (zero? (or exit 1))
           []
           (->> (str/split-lines (or out ""))
                (keep (fn [line]
                        (let [{:keys [k n] stmt :s} (parse-line line)]
                          (when (and n (not= k n) (not (str/blank? n)))
                            (let [nt (set (tokens n))
                                  st (set (tokens stmt))
                                  in-name (filter nt qt)
                                  ;; Statement credit only for what the name
                                  ;; does not already cover: matching the same
                                  ;; token in both fields is one piece of
                                  ;; evidence, not two.
                                  in-stmt (filter (fn [t] (and (st t)
                                                               (not (nt t))))
                                                  qt)
                                  matched (concat in-name in-stmt)]
                              (when (pos? (count matched))
                                ;; Weighted by how much each matched token
                                ;; narrows the space, so a declaration sharing
                                ;; `transgen` outranks one sharing `iff` even
                                ;; though both share exactly one word.
                                ;; `:idf-frac` is what render reads to tell a
                                ;; hit from a name that happens to contain a
                                ;; Mathlib particle — the ranking alone
                                ;; cannot, since the best of a bad field
                                ;; still sorts first.
                                (let [name-idf (reduce + 0.0
                                                       (map #(idf dfm dn %)
                                                            in-name))
                                      stmt-idf (reduce + 0.0
                                                       (map #(idf dfm dn %)
                                                            in-stmt))
                                      got (reduce + 0.0
                                                  (map #(idf dfm dn %)
                                                       matched))]
                                  {:n n :k k :s stmt
                                   :overlap (count matched)
                                   :idf-frac (if (pos? qidf) (/ got qidf) 0.0)
                                   :score (- (+ name-idf
                                                (* statement-weight stmt-idf))
                                              ;; Long names accumulate accidental
                                              ;; particle matches; statements are
                                              ;; uniformly long already, so only
                                              ;; the name is penalised, lightly.
                                             (* 0.05 (count nt)))})))))))
                (sort-by :score >)
                (take k)
                vec)))))))

(def ^:private relevance-floor
  "Share of the query's INFORMATION a name must match to count as a hit.

  Information rather than token count. Counting tokens equally made a match on
  `iff` worth a match on `transgen`, and in the 215,781-declaration index `iff`
  appears in 17,610 names against `transgen`'s 30 — about 600x apart in how
  much they narrow anything. gen-27 asked for \"Relation.TransGen iff exists
  list Chain'\" and got `mem_closure_iff_exists_list`: three shared tokens,
  every one a Mathlib name-particle, clearing a token-count floor while missing
  the whole point of the query.

  Raising a token-count floor would not have helped — it suppresses genuine
  short matches at the same rate. The fix is to stop pretending the tokens are
  worth the same."
  0.4)

(defn relevant?
  "Whether a hit matched enough of the query's information to be called a hit."
  [hit]
  (>= (double (:idf-frac hit 0.0)) relevance-floor))

(defn render
  "The search result as the branch reads it.

  The ranking keeps anything sharing a single token with the query, because a
  near-miss is occasionally the right lead. That made every query look like a
  hit: against 215k declarations there is always SOMETHING sharing a word, so
  the honest answer never appeared. gen-25 opened with 14 searches, 12 artifact
  fetches and zero verification attempts across three branches, hunting a lemma
  whose closest match — `Finite.subset`, for a query about balanced in- and
  out-degree — shared two words out of eight.

  So the names still show, and the confidence does not. A branch that is told
  Mathlib probably lacks this can go and prove it; a branch handed a plausible
  list searches again. What a genuine hit shows is the statement too — that
  is what lets a branch use the lemma without another round trip."
  [hits query]
  (let [qn (count (tokens query))
        strong (filter relevant? hits)]
    (cond
      (empty? hits)
      (str "No Mathlib declaration matched `" query "`. Try the mathematical"
           " vocabulary rather than a description — Mathlib names are built from"
           " the concepts, e.g. `add_comm`, `sqrt_le_sqrt`, `sum_pow`.")

      (empty? strong)
      (str "Nothing in Mathlib matched `" query "` well. The closest names are:\n"
           (str/join "\n" (for [h (take 5 hits)]
                            (str "  " (:k h) " " (:n h)
                                 "  (shares " (:overlap h 0) " of " qn " terms)")))
           "\n\nThese are name overlaps, not necessarily the lemma you want."
           " Mathlib may not have this result — searching again with different"
           " wording usually will not find it either. Proving it directly is"
           " normally the shorter path, and a lemma you state yourself is one"
           " you can then use.")

      :else
      (str "Mathlib matches for `" query "`:\n"
           (str/join "\n" (for [h strong]
                            (str "  " (:k h) " " (:n h)
                                 (when-not (str/blank? (:s h))
                                   ;; The statement is the entire benefit of
                                   ;; the new index: a branch can decide
                                   ;; whether it wants the lemma without
                                   ;; spending a turn fetching the artifact.
                                   (str " :: " (:s h))))))))))
