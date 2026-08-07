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

(defn render
  "Shared artifacts as the block that goes into a branch's next-turn context:
  engine-confirmed by other branches, provenance inline."
  [entries]
  (when (seq entries)
    (str "## Confirmed by other branches — engine-verified\n\n"
         (str/join "\n"
                   (for [{:keys [branch_id kind tier claim]} entries]
                     (str "- [" branch_id " " (name kind) "/" (name tier) "] " claim))))))
