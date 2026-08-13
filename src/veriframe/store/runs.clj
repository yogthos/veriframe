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

(defn reconcile-orphans!
  "Mark every run still claiming to be running as interrupted. Returns how many.

  `status = 'running'` is written once when the beam opens and revisited only
  when it finishes, so a process that dies mid-run leaves the row asserting
  forever: it shows up in the run list and in any 'is anything active' check,
  and nothing in the row itself says otherwise. gen-18 and gen-11 both sat that
  way for days, and the second was filed as its own bug before anyone noticed
  it was the same defect — which is the argument for reconciling rather than
  correcting rows by hand each time.

  Sound only AT STARTUP, and that is the whole trick. In general a row cannot
  tell a live run from a dead one. But the beam only ever runs in this process,
  so at the moment this process comes up, nothing is running by construction
  and every such row is a leftover.

  `interrupted` rather than `failed` or `completed`: the run neither errored nor
  finished, and recording either would be a lie about what happened. `ended_at`
  is set because a run with no end time makes every duration computed from it
  wrong."
  [conn]
  (let [orphans (db/fetch conn ["SELECT id FROM runs WHERE status = 'running'"])]
    (doseq [{:keys [id]} orphans]
      (db/with-writer
        (db/execute! conn
                     ["UPDATE runs SET status = 'interrupted', ended_at = ?
                        WHERE id = ? AND status = 'running'"
                      (db/now) id]))
      (journal/note! conn id :run-finished
                     {:data {:status "interrupted"
                             :reason "no process was running it when the server started"}}))
    (count orphans)))

(defn get-run [conn run-id]
  (db/fetch-one conn ["SELECT * FROM runs WHERE id = ?" run-id]))

(defn last-progress-at
  "When this run last wrote anything to its journal, or nil if it never has.

  Every meaningful step emits an event, so this is the run's heartbeat without
  a heartbeat column: the id index gives it in one indexed row lookup, and
  ordering by id rather than created_at avoids trusting a clock for
  monotonicity."
  [conn run-id]
  (:created_at (db/fetch-one conn ["SELECT created_at FROM events WHERE run_id = ?
                                    ORDER BY id DESC LIMIT 1" run-id])))

(defn stalled?
  "True when a run still claims to be running but has been silent longer than
  `threshold-ms`.

  gen-11 crashed mid-round and sat at status 'running' with no events for the
  rest of the night, and nothing could tell that from a healthy slow round —
  both look like a row saying `running`. Silence alone is not enough (a Lean
  tactic legitimately buys minutes of it), so the threshold belongs above the
  turn deadline; silence plus a status nobody will ever update is the signal.

  Only ever reports on a RUNNING run. A finished one is quiet because it is
  over, which is not the same fault and must not raise the same alarm."
  [conn run-id threshold-ms]
  (let [r (get-run conn run-id)]
    (boolean
     (and r
          (= "running" (:status r))
          (when-let [t (or (last-progress-at conn run-id) (:started_at r))]
            (try
              (> (- (System/currentTimeMillis)
                    (.toEpochMilli (java.time.Instant/parse t)))
                 threshold-ms)
              ;; An unparseable timestamp is a storage fault, not evidence the
              ;; run is wedged. Say nothing rather than cry wolf.
              (catch Throwable _ false)))))))

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

(defn extend-budget!
  "Raise a run's max_turns. Only ever called with an explicitly requested
  value: the resume path keeps the original budget unless one is passed, so
  a crash cannot re-grant turns — extension is a human act, journaled as one."
  [conn run-id max-turns]
  (db/with-writer
    (db/execute! conn ["UPDATE runs SET max_turns = ? WHERE id = ?"
                       max-turns run-id]))
  (journal/note! conn run-id :budget-extended {:data {:max-turns max-turns}}))

(defn reopen-branch!
  "An exhausted branch back to active — the budget-extension path. Branches
  closed for cause (culled, abandoned, done) are never reopened; exhaustion
  is the one closing reason that names the budget rather than the branch."
  [conn run-id branch-id]
  (db/with-writer
    (db/execute! conn ["UPDATE branches SET status = 'active', inactive_reason = NULL
                        WHERE run_id = ? AND id = ? AND status = 'exhausted'"
                       run-id branch-id]))
  (journal/note! conn run-id :branch-reopened {:branch-id branch-id}))

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
