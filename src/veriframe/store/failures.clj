(ns veriframe.store.failures
  "The cross-branch failure log.

  In the TypeScript harness this is a vector re-rendered into every branch's
  context on every turn, so its cost grows with the run and every branch pays
  for every other branch's history whether or not it is relevant. Backed by
  FTS5 it becomes a question instead: give this branch the failures most like
  what it is about to try. Smaller context and a better one.

  The FTS table is standalone rather than external-content, because what gets
  indexed is a projection of claim plus reason and external-content deletes
  require the exact indexed values back. Sync is app-managed here, no triggers."
  (:require [clojure.string :as str]
            [jdbc.core :as jdbc]
            [veriframe.store.db :as db]
            [veriframe.store.journal :as journal]))

(defn record!
  [conn run-id {:keys [branch-id turn tool-name claim reason]}]
  (db/with-writer
    (db/execute! conn
                   ["INSERT INTO failures (run_id, branch_id, turn, tool_name, claim,
                                           reason, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?)"
                    run-id branch-id turn (str tool-name) (str claim) (str reason) (db/now)])
    (let [id (db/last-insert-id conn)]
      (db/execute! conn
                     ["INSERT INTO failures_fts (rowid, claim, reason) VALUES (?, ?, ?)"
                      id (str claim) (str reason)])))
  (journal/note! conn run-id :failure
                 {:branch-id branch-id :turn turn
                  :data {:tool tool-name :claim claim :reason reason}}))

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
  "The failures most like `text`, best match first.

  Returns [] rather than throwing when the query has no usable terms, because
  an empty failure log is a normal state and a branch should not lose its turn
  to a search that found nothing."
  ([conn run-id text] (similar conn run-id text 5))
  ([conn run-id text limit]
   (let [q (fts-query text)]
     (if (str/blank? q)
       []
       (try
         (db/fetch conn
                     ["SELECT f.branch_id, f.turn, f.tool_name, f.claim, f.reason
                       FROM failures_fts fts
                       JOIN failures f ON f.id = fts.rowid
                       WHERE failures_fts MATCH ? AND f.run_id = ?
                       ORDER BY bm25(failures_fts) LIMIT ?"
                      q run-id limit])
         (catch Throwable _ []))))))

(defn recent
  ([conn run-id] (recent conn run-id 10))
  ([conn run-id limit]
   (db/fetch conn ["SELECT branch_id, turn, tool_name, claim, reason FROM failures
                      WHERE run_id = ? ORDER BY id DESC LIMIT ?" run-id limit])))

(defn render
  "Failures as the block that goes into a branch's next-turn context."
  [entries]
  (when (seq entries)
    (str "## Already disproven — do not retry\n\n"
         (str/join "\n"
                   (for [{:keys [branch_id turn tool_name claim reason]} entries]
                     (str "- [" branch_id " t" turn " " tool_name "] " claim
                          "\n  → " reason))))))
