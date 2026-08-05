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

  `category` is :success, :failure, or :neutral, and it is what the cull and
  progress guards read. It is recorded rather than derived later because the
  tool that produced it knows, and a reconstruction would be guessing."
  [conn run-id {:keys [branch-id turn tool-name args result category
                       parse-error auto-repaired assistant-text reasoning-text]}]
  (db/with-writer
    (db/execute! conn
                   ["INSERT INTO turns (run_id, branch_id, turn, tool_name, args, result,
                                        category, parse_error, auto_repaired,
                                        assistant_text, reasoning_text, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    run-id branch-id turn (str tool-name) (js (or args {}))
                    (str result) (some-> category name) parse-error
                    (if auto-repaired 1 0)
                    assistant-text reasoning-text (db/now)]))
  (emit! conn run-id :turn {:branch-id branch-id :turn turn
                            :data {:tool tool-name :category category}}))

(defn turns [conn run-id]
  (db/fetch conn ["SELECT * FROM turns WHERE run_id = ? ORDER BY id" run-id]))

;; --- artifacts --------------------------------------------------------------

(defn record-artifact!
  "A machine-checked result.

  `claim-status` is the confirmed / refuted / ambiguous / existential split.
  The existential bucket is the one that earns its keep: a SAT verdict over
  free variables says a solution exists and does not hand you one, and the
  done gate refuses to let it substantiate a concrete answer."
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
