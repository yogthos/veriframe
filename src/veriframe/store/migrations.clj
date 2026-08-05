;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.store.migrations
  "Schema as numbered, idempotent migrations against PRAGMA user_version.

  Every migration is a VECTOR OF SINGLE STATEMENTS, never one multi-statement
  string. db.sqlite/query calls sqlite3_prepare_v2 with a null tail pointer, so
  a string holding several statements executes only the first and reports no
  error at all. migrations-test asserts the statement count of each migration
  against the objects it should have created, so that failure mode cannot
  return quietly.

  Add a migration by appending to `migrations`; never edit one that shipped.
  Each carries a comment saying what it is for, which is the reason dirge's
  schema is still legible eleven migrations in.")

(def ^:private v1
  ;; The run journal. Everything the loop learns is appended here as it
  ;; happens, not assembled at the end, so a crashed run stays inspectable and
  ;; the read API can serve a live run and a finished one with the same query.
  ["CREATE TABLE IF NOT EXISTS runs (
      id            TEXT PRIMARY KEY,
      problem       TEXT NOT NULL,
      status        TEXT NOT NULL DEFAULT 'running',
      provider      TEXT NOT NULL DEFAULT '',
      model         TEXT NOT NULL DEFAULT '',
      max_turns     INTEGER NOT NULL DEFAULT 0,
      beam_width    INTEGER NOT NULL DEFAULT 0,
      prompt_digest TEXT NOT NULL DEFAULT '',
      final_answer  TEXT,
      started_at    TEXT NOT NULL,
      ended_at      TEXT
    )"

   ;; A branch is an entity with a durable id rather than a value threaded
   ;; through the loop, because an intervention has to be able to name one.
   "CREATE TABLE IF NOT EXISTS branches (
      id              TEXT NOT NULL,
      run_id          TEXT NOT NULL REFERENCES runs(id),
      parent_id       TEXT,
      status          TEXT NOT NULL DEFAULT 'active',
      inactive_reason TEXT,
      thesis          TEXT,
      created_at_turn INTEGER NOT NULL DEFAULT 0,
      PRIMARY KEY (run_id, id)
    )"

   "CREATE TABLE IF NOT EXISTS turns (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id     TEXT NOT NULL REFERENCES runs(id),
      branch_id  TEXT NOT NULL,
      turn       INTEGER NOT NULL,
      tool_name  TEXT NOT NULL DEFAULT '',
      args       TEXT NOT NULL DEFAULT '',
      result     TEXT NOT NULL DEFAULT '',
      category   TEXT,
      parse_error   TEXT,
      auto_repaired INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL
    )"

   "CREATE INDEX IF NOT EXISTS idx_turns_run ON turns(run_id, branch_id, turn)"

   ;; claim_status is the confirmed / refuted / ambiguous / existential split.
   ;; The existential bucket is why this column exists: a SAT verdict over free
   ;; variables says a solution exists and does not hand you one, and the
   ;; done gate refuses to let it substantiate a concrete answer.
   "CREATE TABLE IF NOT EXISTS artifacts (
      id           INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id       TEXT NOT NULL REFERENCES runs(id),
      branch_id    TEXT NOT NULL,
      turn         INTEGER NOT NULL,
      kind         TEXT NOT NULL,
      claim        TEXT NOT NULL,
      code         TEXT NOT NULL DEFAULT '',
      verdict      TEXT,
      witness      TEXT,
      claim_status TEXT NOT NULL,
      tier         TEXT NOT NULL DEFAULT 'fast',
      created_at   TEXT NOT NULL
    )"

   "CREATE INDEX IF NOT EXISTS idx_artifacts_run ON artifacts(run_id, branch_id)"

   ;; The cross-branch failure log. In the TypeScript harness this is a vector
   ;; re-rendered into every branch's context each turn, so it grows without
   ;; bound; backed by FTS5 it becomes a query for the failures most like what
   ;; this branch is about to try.
   "CREATE TABLE IF NOT EXISTS failures (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id     TEXT NOT NULL REFERENCES runs(id),
      branch_id  TEXT NOT NULL,
      turn       INTEGER NOT NULL,
      tool_name  TEXT NOT NULL DEFAULT '',
      claim      TEXT NOT NULL DEFAULT '',
      reason     TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL
    )"

   ;; Standalone FTS5, not external-content: the indexed text is a projection
   ;; (claim plus reason), and external-content deletes require the exact
   ;; indexed values back, which a projection cannot promise. Sync is
   ;; app-managed in store.failures, no triggers.
   "CREATE VIRTUAL TABLE IF NOT EXISTS failures_fts USING fts5(claim, reason)"

   ;; Decision observability: a gate firing records what it expected to happen
   ;; next, and a later turn settles that prediction from the journal with no
   ;; LLM in the path. A gate whose predictions never settle is not steering.
   "CREATE TABLE IF NOT EXISTS gate_firings (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id      TEXT NOT NULL REFERENCES runs(id),
      branch_id   TEXT NOT NULL,
      turn        INTEGER NOT NULL,
      gate        TEXT NOT NULL,
      priority    INTEGER NOT NULL DEFAULT 0,
      message     TEXT NOT NULL DEFAULT '',
      prediction  TEXT NOT NULL DEFAULT '',
      window      INTEGER NOT NULL DEFAULT 0,
      outcome     TEXT,
      settled_at_turn INTEGER,
      created_at  TEXT NOT NULL
    )"

   "CREATE INDEX IF NOT EXISTS idx_gate_firings_run ON gate_firings(run_id, gate)"

   ;; Human directives. Applied at the next branch boundary, never mid-turn:
   ;; a branch inside a provider call or a Lean tactic is not in a state anyone
   ;; should mutate. status carries pending / applied / rejected so a UI can
   ;; show the difference honestly instead of pretending a click took effect.
   "CREATE TABLE IF NOT EXISTS interventions (
      id          INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id      TEXT NOT NULL REFERENCES runs(id),
      branch_id   TEXT,
      kind        TEXT NOT NULL,
      payload     TEXT NOT NULL DEFAULT '',
      issued_by   TEXT NOT NULL DEFAULT 'human',
      status      TEXT NOT NULL DEFAULT 'pending',
      disposition TEXT,
      created_at  TEXT NOT NULL,
      applied_at_turn INTEGER
    )"

   "CREATE INDEX IF NOT EXISTS idx_interventions_pending
      ON interventions(run_id, status)"

   ;; The event cursor the tail endpoint reads. One row per appended event, so
   ;; GET /v1/runs/:id/journal?since=N is a single indexed range scan and the
   ;; UI needs no cooperation from the loop.
   "CREATE TABLE IF NOT EXISTS events (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id     TEXT NOT NULL REFERENCES runs(id),
      branch_id  TEXT,
      turn       INTEGER,
      kind       TEXT NOT NULL,
      data       TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL
    )"

   "CREATE INDEX IF NOT EXISTS idx_events_cursor ON events(run_id, id)"])

(def migrations
  "Ordered. Index 0 is migration 1; PRAGMA user_version holds the count applied."
  [v1])
