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
  descriptive (`Real.sqrt_le_sqrt`, `Nat.add_comm`, `Finset.sum_pow`), so token
  overlap on the NAME carries most of the signal, and an embedding index would
  add a model dependency and a build step to buy recall this problem may not
  need. If keyword recall proves insufficient the index is the thing to
  replace, not the interface.

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

(def ^:private decl-pattern
  "POSIX ERE, handed to grep rather than run in-process. Deliberately not a
  ripgrep pattern: `rg` is often a shell function or absent, while grep is
  always a real binary on PATH, and a subprocess cannot call a shell function."
  "^[[:space:]]*(private |protected |noncomputable |nonrec )*(theorem|lemma|def|abbrev|instance|structure|inductive) [^[:space:]:({[]+")

(defn- mathlib-root [workspace]
  (io/file workspace ".lake" "packages" "mathlib" "Mathlib"))

(defn build-index!
  "Extract every declaration from the Mathlib source and cache it.

  Shelled out to ripgrep, which is the plan's own stated fallback for this
  index and turned out to be necessary rather than optional: the same
  extraction run in-process over Mathlib's 7871 files had not finished after
  thirty minutes, while ripgrep does it in a quarter of a second. Regex over
  hundreds of megabytes is not what this runtime is for, and shelling out to
  the tool that is good at it beats optimizing the loop.

  Degrades to an empty index rather than throwing: premise search is an aid,
  and a branch should not lose its turn because the index is missing."
  [{:keys [workspace]}]
  (let [root (mathlib-root workspace)]
    (when-not (.exists root)
      (throw (ex-info (str "No Mathlib source at " root " — run tools/setup-lean.sh")
                      {:root (str root)})))
    (log/info "building the Mathlib premise index")
    (let [{:keys [out exit]} (proc/run {:timeout-ms 180000}
                                       "grep" "-rhoE" decl-pattern
                                       "--include=*.lean"
                                       (.getAbsolutePath root))
          text (if (zero? (or exit 1)) (or out "") "")]
      (when (str/blank? text)
        (log/warn "the Mathlib index came back empty"))
      (io/make-parents cache-path)
      ;; Plain text, one declaration per line, NOT parsed into memory. Decoding
      ;; 215k maps and holding them was slower in this runtime than the scan it
      ;; replaced, and searching them meant tokenizing all 215k per query.
      (spit cache-path text)
      (log/info "indexed" (count (str/split-lines text)) "declarations")
      cache-path)))

(defn- index-file [cfg]
  (let [f (io/file cache-path)]
    (when-not (.exists f) (build-index! cfg))
    (.getAbsolutePath f)))

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
  "Token -> how many declaration names contain it, plus the declaration count.

  One pass over the index, memoised for the process. The index is already on
  disk for grep, so this needs no new artefact and stays correct as Mathlib
  moves — the alternative, a hand-curated stoplist of common name-particles,
  would need maintaining and would still be a guess."
  (atom nil))

(defn- doc-freq
  [cfg]
  (or @df-cache
      (let [{:keys [df n]}
            (with-open [r (io/reader (index-file cfg))]
              (reduce (fn [{:keys [df n]} line]
                        (let [parts (str/split (str/trim line) #"\s+")
                              nm (last parts)]
                          (if (or (nil? nm) (str/blank? nm))
                            {:df df :n n}
                            {:n (inc n)
                             :df (reduce (fn [m t] (update m t (fnil inc 0)))
                                         df
                                         (distinct (tokens nm)))})))
                      {:df {} :n 0}
                      (line-seq r)))]
        (reset! df-cache {:df df :n (max n 1)}))))

(defn search
  "Top-k Mathlib declarations matching `query`.

  grep narrows 215k lines to the handful that contain any query token, and only
  those are ranked in-process. Scored on the NAME, because that is where
  Mathlib puts the meaning: the convention is descriptive, so a query's words
  land in `Real.sqrt_le_sqrt` directly."
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
                        (let [parts (str/split (str/trim line) #"\s+")
                              ;; The declaration keyword, not the first token:
                              ;; `protected theorem foo` and `noncomputable def
                              ;; bar` both lead with a modifier.
                              kind (or (first (filter #{"theorem" "lemma" "def" "abbrev"
                                                        "instance" "structure" "inductive"}
                                                      parts))
                                       (first parts))
                              nm (last parts)]
                          (when (and nm (not= kind nm))
                            (let [nt (set (tokens nm))
                                  overlap (count (filter nt qt))]
                              (when (pos? overlap)
                                ;; Weighted by how much each matched token
                                ;; narrows the space, so a name sharing
                                ;; `transgen` outranks one sharing `iff` even
                                ;; though both share exactly one word.
                                ;; `:idf-frac` is what render reads to tell a
                                ;; hit from a name that happens to contain a
                                ;; Mathlib particle — the ranking alone cannot,
                                ;; since the best of a bad field still sorts
                                ;; first.
                                (let [matched (filter nt qt)
                                      got (reduce + 0.0 (map #(idf dfm dn %) matched))]
                                  {:n nm :k kind :overlap overlap
                                   :idf-frac (if (pos? qidf) (/ got qidf) 0.0)
                                   :score (- got (* 0.05 (count nt)))})))))))
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
  list searches again."
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
           (str/join "\n" (for [h strong] (str "  " (:k h) " " (:n h))))))))
