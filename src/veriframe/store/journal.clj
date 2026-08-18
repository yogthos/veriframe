;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.store.journal
  "Append-only writes for a run, and the queries that read them back.

  Everything is written when it happens rather than assembled at the end. Two
  things depend on that. A run killed mid-flight stays fully inspectable, which
  is what makes SQLite worth having over an in-memory result. And the read API
  serves a live run and a finished one with the same query, so a UI needs no
  cooperation from the loop.

  Every append also emits an event carrying a monotonic cursor, which is what
  `GET /v1/runs/:id/journal?since=N` reads. The loop calls these; nothing calls
  the loop."
  (:require [clojure.data.json :as json]
            [jdbc.core :as jdbc]
            [veriframe.events :as events]
            [veriframe.store.db :as db]))

(defn- js [v] (if (string? v) v (json/write-str v)))

(defn- emit!
  "Record an event and publish it. The row is the durable copy the tail
  endpoint reads; the publish is for anything watching live."
  [conn run-id kind {:keys [branch-id turn data]}]
  (let [now (db/now)]
    (db/with-writer
      (db/execute! conn
                     ["INSERT INTO events (run_id, branch_id, turn, kind, data, created_at)
                       VALUES (?, ?, ?, ?, ?, ?)"
                      run-id branch-id turn (name kind) (js (or data {})) now])
      (let [id (db/last-insert-id conn)]
        (events/publish! {:id id :run-id run-id :branch-id branch-id
                          :turn turn :kind kind :data data :created-at now})
        id))))

;; --- turns ------------------------------------------------------------------

(defn record-turn!
  "One model turn: what it called, with what, and what came back.

  `category` is :success, :failure, :neutral or :mechanics — the last for a
  turn that produced no usable tool call, kept distinct so the record can tell
  a branch that was wrong from one that could not format a fence. It is what
  the cull and
  progress guards read. It is recorded rather than derived later because the
  tool that produced it knows, and a reconstruction would be guessing."
  [conn run-id {:keys [branch-id turn tool-name args result category
                       parse-error auto-repaired assistant-text reasoning-text
                       usage policy-refusal?]}]
  (db/with-writer
    (db/execute! conn
                   ["INSERT INTO turns (run_id, branch_id, turn, tool_name, args, result,
                                        category, parse_error, auto_repaired,
                                        assistant_text, reasoning_text, created_at,
                                        prompt_tokens, completion_tokens, total_tokens,
                                        cache_hit_tokens, cache_miss_tokens,
                                        policy_refusal)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    run-id branch-id turn (str tool-name) (js (or args {}))
                    (str result) (some-> category name) parse-error
                    (if auto-repaired 1 0)
                    assistant-text reasoning-text (db/now)
                    ;; nil, not 0, when the turn had no response to cost —
                    ;; see migration v4. `usage` is absent on the
                    ;; provider-error path by construction.
                    (:prompt-tokens usage) (:completion-tokens usage)
                    (:total-tokens usage)
                    (:cache-hit-tokens usage) (:cache-miss-tokens usage)
                    (if policy-refusal? 1 0)]))
  (emit! conn run-id :turn {:branch-id branch-id :turn turn
                            :data {:tool tool-name :category category}}))

(defn turns
  "Every turn of a run, whole rows. `assistant_text` comes back with them, so
  this is what resume replays from — and it is why nothing that merely
  DISPLAYS turns should call it. See `branch-turns`."
  [conn run-id]
  (db/fetch conn ["SELECT * FROM turns WHERE run_id = ? ORDER BY id" run-id]))

(defn branch-turns
  "One branch's turns, carrying only what a reader renders.

  Both halves matter. Filtering by branch in SQL uses the
  (run_id, branch_id, turn) index instead of dragging the whole run into
  memory to throw most of it away; dropping assistant_text and
  reasoning_text drops the bulk, which on one real run was 5.5MB against
  62KB of results. The branch panel used to fetch all of it, spend over two
  minutes doing so, and exceed the client's socket timeout — so the branch
  never rendered at all."
  [conn run-id branch-id]
  (db/fetch conn
            ["SELECT id, run_id, branch_id, turn, tool_name, args, result,
                     category, parse_error, auto_repaired, created_at
                FROM turns
               WHERE run_id = ? AND branch_id = ?
               ORDER BY turn, id"
             run-id branch-id]))

;; --- artifacts --------------------------------------------------------------

(defn record-artifact!
  "A machine-checked result.

  `claim-status` is the confirmed / refuted / ambiguous / existential /
  empirical / unfaithful split. Two of those earn their keep by being neither
  a yes nor a no: existential, where a SAT verdict over free variables says a
  solution exists and does not hand you one, and empirical, where a
  computation produced a number and decided nothing. The done gate refuses to
  let either substantiate an answer on its own."
  [conn run-id {:keys [branch-id turn kind claim code verdict witness
                       claim-status tier]}]
  (db/with-writer
    (db/execute! conn
                   ["INSERT INTO artifacts (run_id, branch_id, turn, kind, claim, code,
                                            verdict, witness, claim_status, tier, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    run-id branch-id turn (name kind) claim (str code)
                    (some-> verdict name) (when witness (js witness))
                    (name claim-status) (name (or tier :fast)) (db/now)]))
  (emit! conn run-id :artifact {:branch-id branch-id :turn turn
                                :data {:kind kind :claim claim
                                       :claim-status claim-status}}))

(defn artifacts
  ([conn run-id]
   (db/fetch conn ["SELECT * FROM artifacts WHERE run_id = ? ORDER BY id" run-id]))
  ([conn run-id branch-id]
   (db/fetch conn ["SELECT * FROM artifacts WHERE run_id = ? AND branch_id = ? ORDER BY id"
                     run-id branch-id])))

(defn confirmed-artifacts [conn run-id branch-id]
  (db/fetch conn ["SELECT * FROM artifacts
                     WHERE run_id = ? AND branch_id = ? AND claim_status = 'confirmed'
                     ORDER BY id" run-id branch-id]))

(defn ledger
  "What the run has settled, as state rather than narrative.

  Two lists: what is established and what is ruled out. Read from `artifacts`
  rather than `shared_artifacts` for two reasons — the authoritative table
  already carries `claim_status` where the shared pool does not, and the
  ledger has to be COMPLETE. The shared block samples the top five by FTS
  relevance, which is right when the payload is a large encoding and wrong
  here: the whole value of a ledger is that a branch can trust the absence of
  a line.

  `refuted` is the half nothing carried before. 127 of them exist across the
  project's history and loop/shareable? admits only `:confirmed`, so a branch
  was told what siblings had proved and never what they had disproved — and a
  refutation prunes a direction where a confirmation only adds a fact.

  `unfaithful` is deliberately excluded despite being numerous: the encoding
  did not establish the claim, so carrying it would spread an assertion
  nothing verified. `empirical` and `existential` are excluded for now as an
  explicit choice rather than an oversight — a measurement is not a settled
  fact and would need its own section to avoid being read as one.

  Own-branch rows are INCLUDED, unlike `corroborating-artifacts`. Re-reading
  your own established facts as a list is the point; the alternative is
  scanning an eighty-turn transcript for them.

  `:inherited` is what a seed carried in. `seed-from-run!` copies a prior
  run's confirmed artifacts into `shared_artifacts`, NOT into `artifacts`, so
  a seeded run's ledger read `established 0` while the run held eleven
  verified lemmas — telling the branch the opposite of the truth and leaving
  the problem statement's hand-written summary as the only route to them,
  which is the fragility seeding exists to remove.

  Cheap: gen-20's entire confirmed set is 1,495 characters of claim text."
  [conn run-id]
  (let [rows (db/fetch conn
                       ["SELECT id, branch_id, turn, kind, tier, claim, claim_status
                         FROM artifacts
                         WHERE run_id = ?
                           AND claim_status IN ('confirmed', 'refuted', 'sketch')
                         ORDER BY id" run-id])
        by-status (group-by :claim_status rows)]
    {:established (vec (get by-status "confirmed" []))
     :ruled-out (vec (get by-status "refuted" []))
     ;; Plans, not results: a sketch elaborates and cites real lemmas but
     ;; proves nothing, so it rides in its own list under its own prefix
     ;; rather than joining either half of what the run has SETTLED.
     :sketches (vec (get by-status "sketch" []))
     ;; Seeded rows only. A live branch's shared artifacts are already in
     ;; `artifacts` above, so including them here would double-count.
     :inherited (vec (db/fetch conn
                               ["SELECT id, branch_id, turn, kind, tier, claim
                                 FROM shared_artifacts
                                 WHERE run_id = ? AND branch_id LIKE 'seed:%'
                                 ORDER BY id" run-id]))}))

(defn branch-turn
  "One turn of one branch, whole.

  What `fetch_turn` serves: compaction unloads a branch's early turns to one
  line each, and this is how a line gets opened again. Scoped to the branch
  because the digest is of the branch's OWN history — a sibling's turn is not
  what `t8` referred to, and cross-branch reading is what the shared-artifact
  block and the settled-state ledger are for."
  [conn run-id branch-id turn]
  (db/fetch-one conn ["SELECT * FROM turns
                       WHERE run_id = ? AND branch_id = ? AND turn = ?
                       ORDER BY id LIMIT 1"
                      run-id branch-id turn]))

(defn shared-artifact-by-id
  "One shared-pool row of this run, whole, including its encoding.

  A separate table from `artifacts` and therefore a separate id space, which
  is why the ledger renders seeded entries as `s#N` and this run's own as
  `a#N`. Run-scoped for the same reason as `artifact-by-id`."
  [conn run-id id]
  (db/fetch-one conn ["SELECT * FROM shared_artifacts WHERE run_id = ? AND id = ?"
                      run-id id]))

(defn artifact-by-id
  "One artifact of this run, whole, including its encoding.

  Scoped to the run on purpose: cross-run reach would let a branch cite
  something the run never established, which the done gate's evidence rungs
  exist to prevent."
  [conn run-id id]
  (db/fetch-one conn ["SELECT * FROM artifacts WHERE run_id = ? AND id = ?"
                      run-id id]))

(defn corroborating-artifacts
  "Everything the rest of the run confirmed or measured.

  A fork opens with its parent's confirmed claims quoted into its first
  message, and every branch sees the shared-artifact block. Both are context
  and neither is an artifact, so the done gate's coverage rung — which read
  only the branch's own list — refused answers that cited what the harness had
  just handed the branch (vf-b9c). Same run, same database, same engines: a
  sibling's confirmed claim is support.

  Own-branch rows are excluded; the caller already holds those."
  [conn run-id branch-id]
  (db/fetch conn ["SELECT * FROM artifacts
                     WHERE run_id = ? AND branch_id <> ?
                       AND claim_status IN ('confirmed', 'empirical')
                     ORDER BY id" run-id branch-id]))

;; --- gate firings -----------------------------------------------------------

(defn record-gate!
  "A gate fired, with what it expects to happen next.

  The prediction is the point. A gate that cannot say what should change is a
  gate whose effect nobody can check, and settling these deterministically from
  later turns is what turns a steering guess into something falsifiable."
  [conn run-id {:keys [branch-id turn gate priority message prediction window]}]
  ;; Returns the gate_firings row id, NOT the event id. The caller holds this
  ;; to settle the prediction later, and returning the event id instead means
  ;; every settle updates a row that does not exist, leaving the whole tally
  ;; permanently open. That is what the first live run showed.
  (let [id (db/with-writer
             (db/execute! conn
                            ["INSERT INTO gate_firings (run_id, branch_id, turn, gate, priority,
                                                        message, prediction, window, created_at)
                              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                             run-id branch-id turn (name gate) (or priority 0)
                             (str message) (str prediction) (or window 0) (db/now)])
             (db/last-insert-id conn))]
    (emit! conn run-id :gate {:branch-id branch-id :turn turn
                              :data {:gate gate :prediction prediction}})
    id))

(defn settle-gate!
  "Record whether a firing's prediction came true."
  [conn firing-id outcome settled-turn]
  (db/with-writer
    (db/execute! conn
                   ["UPDATE gate_firings SET outcome = ?, settled_at_turn = ? WHERE id = ?"
                    (name outcome) settled-turn firing-id])))

(defn unsettled-gates [conn run-id branch-id]
  (db/fetch conn ["SELECT * FROM gate_firings
                     WHERE run_id = ? AND branch_id = ? AND outcome IS NULL
                     ORDER BY id" run-id branch-id]))

(defn gate-firings [conn run-id]
  (db/fetch conn ["SELECT * FROM gate_firings WHERE run_id = ? ORDER BY id" run-id]))

(defn gate-tally
  "Per gate: how often it fired, and how its predictions settled. A gate that
  never fires across a benchmark sweep is either dead or guarding something the
  probe set should be provoking; a gate whose predictions never settle is not
  steering anything."
  [conn run-id]
  (db/fetch conn
              ["SELECT gate,
                       count(*) AS fired,
                       sum(CASE WHEN outcome = 'met' THEN 1 ELSE 0 END) AS met,
                       sum(CASE WHEN outcome = 'unmet' THEN 1 ELSE 0 END) AS unmet,
                       sum(CASE WHEN outcome IS NULL THEN 1 ELSE 0 END) AS open
                FROM gate_firings WHERE run_id = ? GROUP BY gate ORDER BY fired DESC"
               run-id]))

;; --- events -----------------------------------------------------------------

(defn events-since
  "Everything after `cursor`. One indexed range scan, which is all a polling
  UI needs and works over any HTTP server."
  ([conn run-id cursor] (events-since conn run-id cursor 500))
  ([conn run-id cursor limit]
   (db/fetch conn ["SELECT * FROM events WHERE run_id = ? AND id > ? ORDER BY id LIMIT ?"
                     run-id (or cursor 0) limit])))

(defn note!
  "A free-form journal entry, for anything without a table of its own."
  [conn run-id kind data]
  (emit! conn run-id kind data))
