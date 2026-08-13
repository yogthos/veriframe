;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.store.artifacts
  "The run-scoped shared confirmed-artifact log, the twin of the failure log.

  Branches already share what was DISPROVEN; this shares what an engine
  CONFIRMED, with provenance inline — which branch proved it, with which
  engine, at which tier — so a consumer can see where a lemma came from.
  The difference from UCLA's harness is the entry condition: there, proven
  results were self-reported and trust had to be taken on faith. Here only
  engine-confirmed claim-statuses ever enter, so a branch cannot talk its own
  results into the pool. The cost is beam diversity: branches may converge on
  shared lemmas instead of exploring. That is why the flag defaults to off and
  why sweep-widths runs the beam both ways.

  Like the failure log this is FTS5-backed, a query for the lemmas most like
  what a branch is about to try rather than a vector re-rendered whole into
  every context. The FTS table is standalone, and sync is app-managed here."
  (:require [clojure.string :as str]
            [jdbc.core :as jdbc]
            [veriframe.llm.message :as message]
            [veriframe.store.db :as db]
            [veriframe.store.journal :as journal]))

(defn record!
  "A confirmed artifact into the shared log. Callers gate on claim-status and
  the config flag; this function only writes."
  [conn run-id {:keys [branch-id turn kind tier claim code]}]
  (db/with-writer
    (db/execute! conn
                   ["INSERT INTO shared_artifacts (run_id, branch_id, turn, kind, tier,
                                                   claim, code, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    run-id branch-id turn (name kind) (name (or tier :fast))
                    (str claim) (str code) (db/now)])
    (let [id (db/last-insert-id conn)]
      (db/execute! conn
                     ["INSERT INTO shared_artifacts_fts (rowid, claim) VALUES (?, ?)"
                      id (str claim)]))))

(defn seed-from-run!
  "Copy `source-run-id`'s engine-confirmed artifacts into `run-id`'s shared
  log, for cross-run campaigns: a new run continues from a prior one's
  verified results without inheriting its dead ends.

  Claim AND code cross over — the code is what lets a branch re-confirm an
  inherited lemma in one cheap turn instead of reconstructing the encoding.
  The branch id is prefixed `seed:` so provenance is visible in the context
  block and no live branch's own-branch exclusion hides a seed. Only
  confirmed claim-statuses cross; refuted and existential stay behind. The
  done gate still requires in-run re-verification, so nothing inherited can
  ship on faith. Returns the number of artifacts seeded."
  [conn run-id source-run-id]
  (let [rows (db/fetch conn
                       ["SELECT branch_id, kind, tier, claim, code FROM artifacts
                         WHERE run_id = ? AND claim_status = 'confirmed' ORDER BY id"
                        source-run-id])]
    (doseq [r rows]
      (record! conn run-id {:branch-id (str "seed:" (:branch_id r))
                            :turn 0
                            :kind (keyword (:kind r))
                            :tier (keyword (:tier r))
                            :claim (:claim r)
                            :code (:code r)}))
    (journal/note! conn run-id :run-seeded
                   {:data {:source source-run-id :artifacts (count rows)}})
    (count rows)))

(defn- fts-query
  "Turn free text into an FTS5 OR query.

  FTS5's query language treats several characters as operators, so raw model
  prose is not a safe query string. Words are extracted and quoted; anything
  shorter than three characters is dropped as noise."
  [text]
  (->> (str/split (str/lower-case (or text "")) #"[^a-z0-9]+")
       (filter #(>= (count %) 3))
       distinct
       (take 12)
       (map #(str "\"" % "\""))
       (str/join " OR ")))

(defn similar
  "The shared artifacts most like `text`, best match first.

  Returns [] rather than throwing when the query has no usable terms, because
  an empty shared log is a normal state and a branch should not lose its turn
  to a search that found nothing."
  ([conn run-id text] (similar conn run-id text 5))
  ([conn run-id text limit]
   (let [q (fts-query text)]
     (if (str/blank? q)
       []
       (try
         (db/fetch conn
                     ["SELECT sa.id, sa.branch_id, sa.turn, sa.kind, sa.tier, sa.claim, sa.code
                       FROM shared_artifacts_fts fts
                       JOIN shared_artifacts sa ON sa.id = fts.rowid
                       WHERE shared_artifacts_fts MATCH ? AND sa.run_id = ?
                       ORDER BY bm25(shared_artifacts_fts) LIMIT ?"
                      q run-id limit])
         (catch Throwable _ []))))))

(defn recent
  ([conn run-id] (recent conn run-id 10))
  ([conn run-id limit]
   (db/fetch conn ["SELECT id, branch_id, turn, kind, tier, claim, code FROM shared_artifacts
                      WHERE run_id = ? ORDER BY id DESC LIMIT ?" run-id limit])))

(def ^:private max-shared-code-chars
  "How much of a shared artifact's code the block carries.

  A theorem STATEMENT is what a sibling needs in order to build on a lemma;
  the proof body is not. Generous enough for a Lean signature with a dozen
  hypotheses, small enough that five entries cannot crowd out the branch's own
  context — the block re-renders every turn, so its size is a per-turn cost."
  700)

(def ^:private max-ledger-claim-chars
  "How much of a claim the ledger shows.

  A claim is often a full mathematical statement — gen-18's average is around
  340 characters, and its 79 confirmed artifacts render to ~6,800 tokens
  unabridged. The ledger's job is to let a branch see WHAT is settled and
  decide what to try; the exact statement is one `fetch_artifact` away, so the
  headline is what belongs here."
  180)

(defn- ledger-line [{:keys [id branch_id kind tier claim]}]
  (let [c (str/trim (str claim))
        c (if (> (count c) max-ledger-claim-chars)
            (str (subs c 0 max-ledger-claim-chars) " …")
            c)]
    (str "- [a#" id " " branch_id " " (name (or kind "?")) "/" (name (or tier "?")) "] " c)))

(defn render-ledger
  "The run's settled state, as a block for a branch's next-turn context.

  Two sections, never one list with a status column: a refutation formatted
  like a confirmation is worse than not sharing it at all, and a model
  skimming a single list will merge them. Established first, because it is
  what a branch builds on; ruled out second, because it is what stops a branch
  repeating a closed line.

  Ids are handles, not decoration — `a#12` is what `fetch_artifact` takes, so
  the encodings stay out of the block and cost a turn only when wanted."
  [{:keys [established ruled-out]}]
  (when (or (seq established) (seq ruled-out))
    (str message/ledger-open "\n"
         "## What this run has settled\n\n"
         (when (seq established)
           (str "### Established — engine-verified\n"
                (str/join "\n" (map ledger-line established)) "\n\n"))
         (when (seq ruled-out)
           (str "### Ruled out — engine-REFUTED, do not re-attempt these\n"
                (str/join "\n" (map ledger-line ruled-out)) "\n\n"))
         "Fetch any encoding with `fetch_artifact` and its id.\n"
         message/ledger-close)))

(defn prefer-in-run
  "In-run artifacts first, seeded ones after, order preserved within each.

  A completed run contributes its whole pool at turn 0 while the live run's
  starts empty, so without this seeds win on volume from the start and keep
  winning: gen-20 served 67 seeded artifacts against 24 of its own, spending
  three quarters of the channel re-telling a prior run's results.

  Ranked rather than filtered. A campaign that seeds without restating the
  prior results in its problem statement would lose them entirely, and
  `seed-from-run!` exists for exactly that case."
  [entries]
  (let [seed? #(str/starts-with? (str (:branch_id %)) "seed:")]
    (into (vec (remove seed? entries)) (filter seed? entries))))

(defn render
  "Shared artifacts as the block that goes into a branch's next-turn context:
  engine-confirmed by other branches, provenance inline.

  Carries the CODE as well as the claim. Three gen-20 branches proved the same
  scalarization lemma while being shown each other's claims five times each —
  a branch cannot cite a theorem statement it has never seen, so re-deriving
  was the only move open to it. The code was already being selected by
  `similar` and `recent`, and `seed-from-run!` copies it precisely so an
  inherited lemma can be re-confirmed in one cheap turn; only this function
  dropped it."
  [entries]
  (when (seq entries)
    (str "## Confirmed by other branches — engine-verified\n\n"
         "Cite these by name and re-verify in one call; do not re-derive them.\n\n"
         (str/join "\n"
                   (for [{:keys [branch_id kind tier claim code]} (prefer-in-run entries)]
                     (str "- [" branch_id " " (name kind) "/" (name tier) "] " claim
                          (when-not (str/blank? (str code))
                            (str "\n  ```\n  "
                                 (let [c (str/trim (str code))]
                                   (str/replace (if (> (count c) max-shared-code-chars)
                                                  (str (subs c 0 max-shared-code-chars)
                                                       "\n… [truncated]")
                                                  c)
                                                "\n" "\n  "))
                                 "\n  ```"))))))))
