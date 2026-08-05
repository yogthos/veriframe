;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.store.runs
  "Run and branch lifecycle.

  A run has an identity and a lifecycle independent of the HTTP request that
  started it, so `POST /v1/chat/completions` can block on one for OpenAI
  compatibility while `POST /v1/runs` starts one and returns. A branch is an
  entity with a durable id rather than a value threaded through the loop,
  because an intervention has to be able to name one."
  (:require [clojure.data.json :as json]
            [jdbc.core :as jdbc]
            [veriframe.store.db :as db]
            [veriframe.store.journal :as journal]))

(defn start-run!
  "Open a run and return its id."
  [conn {:keys [problem provider model max-turns beam-width prompt-digest]}]
  (let [id (str (random-uuid))]
    (db/with-writer
      (db/execute! conn
                     ["INSERT INTO runs (id, problem, status, provider, model, max_turns,
                                         beam_width, prompt_digest, started_at)
                       VALUES (?, ?, 'running', ?, ?, ?, ?, ?, ?)"
                      ;; The columns are NOT NULL DEFAULT '', and a DEFAULT does
                      ;; not apply to an explicitly-inserted NULL, so these
                      ;; coerce rather than relying on the schema.
                      id problem (if provider (name provider) "") (or model "")
                      (or max-turns 0) (or beam-width 1) (or prompt-digest "")
                      (db/now)]))
    (journal/note! conn id :run-started {:data {:problem problem :model model}})
    id))

(defn finish-run!
  [conn run-id status final-answer]
  (db/with-writer
    (db/execute! conn
                   ["UPDATE runs SET status = ?, final_answer = ?, ended_at = ? WHERE id = ?"
                    (name status) final-answer (db/now) run-id]))
  (journal/note! conn run-id :run-finished {:data {:status status}}))

(defn get-run [conn run-id]
  (db/fetch-one conn ["SELECT * FROM runs WHERE id = ?" run-id]))

(defn list-runs
  ([conn] (list-runs conn 50))
  ([conn limit]
   (db/fetch conn ["SELECT * FROM runs ORDER BY started_at DESC LIMIT ?" limit])))

(defn open-branch!
  [conn run-id {:keys [branch-id parent-id created-at-turn]}]
  (db/with-writer
    (db/execute! conn
                   ["INSERT INTO branches (id, run_id, parent_id, status, created_at_turn)
                     VALUES (?, ?, ?, 'active', ?)"
                    branch-id run-id parent-id (or created-at-turn 0)]))
  (journal/note! conn run-id :branch-opened
                 {:branch-id branch-id :data {:parent parent-id}})
  branch-id)

(defn close-branch!
  [conn run-id branch-id status reason]
  (db/with-writer
    (db/execute! conn
                   ["UPDATE branches SET status = ?, inactive_reason = ?
                     WHERE run_id = ? AND id = ?"
                    (name status) reason run-id branch-id]))
  (journal/note! conn run-id :branch-closed
                 {:branch-id branch-id :data {:status status :reason reason}}))

(defn set-thesis!
  "The branch's current structural plan. Overwriting is allowed — committing to
  a different route is a legitimate move — and the change is journalled."
  [conn run-id branch-id thesis]
  (db/with-writer
    (db/execute! conn
                   ["UPDATE branches SET thesis = ? WHERE run_id = ? AND id = ?"
                    (json/write-str thesis) run-id branch-id]))
  (journal/note! conn run-id :thesis {:branch-id branch-id :data thesis}))

(defn branches [conn run-id]
  (db/fetch conn ["SELECT * FROM branches WHERE run_id = ? ORDER BY id" run-id]))

(defn get-branch [conn run-id branch-id]
  (db/fetch-one conn ["SELECT * FROM branches WHERE run_id = ? AND id = ?"
                        run-id branch-id]))
