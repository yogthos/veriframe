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

(def ^:private v3
  ;; The run-scoped shared confirmed-artifact log, twin of the failure log.
  ;; Branches already share what was disproven; this shares what an engine
  ;; CONFIRMED, with provenance (branch, engine, tier) inline. Only
  ;; engine-confirmed artifacts enter — never self-reports, which is the
  ;; difference from UCLA's harness. Off by default because shared lemmas can
  ;; cost beam diversity; sweep-widths runs it both ways to find out.
  ["CREATE TABLE IF NOT EXISTS shared_artifacts (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      run_id     TEXT NOT NULL REFERENCES runs(id),
      branch_id  TEXT NOT NULL,
      turn       INTEGER NOT NULL,
      kind       TEXT NOT NULL DEFAULT '',
      tier       TEXT NOT NULL DEFAULT 'fast',
      claim      TEXT NOT NULL DEFAULT '',
      code       TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL
    )"
   ;; Standalone FTS5 like failures_fts: the indexed text is a projection
   ;; (claim alone here), and sync is app-managed in store.artifacts.
   "CREATE VIRTUAL TABLE IF NOT EXISTS shared_artifacts_fts USING fts5(claim)"])

(def ^:private v2
  ;; What the model actually said.
  ;;
  ;; v1 stored the tool call and the result but not the prose around it, so a
  ;; turn that produced no tool call at all recorded only that fact. Nine of
  ;; twenty turns in a Lean run came back "__no_call__" and there was no way to
  ;; ask why, because the one artefact that would answer it was the one thing
  ;; not kept. A harness whose whole thesis is that decisions must be
  ;; inspectable after the fact should not throw away the decision.
  ;;
  ;; Nullable, because the provider-error path has no response to record.
  ["ALTER TABLE turns ADD COLUMN assistant_text TEXT"
   ;; The reasoning block, split out rather than left inline. Reasoning models
   ;; put most of their output here and it dwarfs the answer, so a UI wants to
   ;; fold it and a query that greps the response should not have to wade
   ;; through it. Empty for models that do not emit one.
   "ALTER TABLE turns ADD COLUMN reasoning_text TEXT"])

(def ^:private v4
  ;; What each turn cost.
  ;;
  ;; The adapter parsed usage and client/chat returned it, and the agent loop
  ;; dropped it: nothing outside the bench harness and the raw passthrough API
  ;; ever read it, and turns had no columns to put it in. So the one number a
  ;; harness whose operating rule is "a generation is hours of provider spend"
  ;; most needs was the number it never kept.
  ;;
  ;; Nullable throughout, deliberately. The provider-error path has no response
  ;; and therefore no usage, and a zero there would claim the call was free —
  ;; summing it would under-report the run rather than admit the gap.
  ["ALTER TABLE turns ADD COLUMN prompt_tokens INTEGER"
   "ALTER TABLE turns ADD COLUMN completion_tokens INTEGER"
   "ALTER TABLE turns ADD COLUMN total_tokens INTEGER"
   ;; The prefix-cache split, when the provider reports one. Each branch
   ;; carries its own growing message list, so a beam of five holds five
   ;; diverging prefixes; whether that is cheap is the question these answer.
   "ALTER TABLE turns ADD COLUMN cache_hit_tokens INTEGER"
   "ALTER TABLE turns ADD COLUMN cache_miss_tokens INTEGER"])

(def ^:private v5
  ;; One bit per turn: whether the tool call was declined by harness phase
  ;; policy (vf-b25/vf-eaw). The cull record has to be able to tell a
  ;; declined call — a perfectly well-formed one the harness refused — from a
  ;; malformed fence, or the reason string lies in the permanent record
  ;; (gen-30 B3.2 was culled with exactly that false reason).
  ["ALTER TABLE turns ADD COLUMN policy_refusal INTEGER"])

(def ^:private v6
  ;; What the harness has learned does not compile any more (vf-ppt).
  ;;
  ;; verify_lean already detects this exactly: the cited block is prepended so
  ;; its line range is known, and an error inside it means the branch's own
  ;; snippet was never reached. That knowledge was used for one message to one
  ;; branch and discarded — gen-33 rediscovered the same rotten artifacts 26
  ;; times across all six branches, and what finally stopped it was a human
  ;; auditing all 83 by hand.
  ;;
  ;; Keyed by HANDLE rather than artifact id because the two id spaces differ:
  ;; `a#` is this run's artifacts table, `s#` the shared pool it inherited.
  ["CREATE TABLE IF NOT EXISTS artifact_rot (
      run_id     TEXT NOT NULL,
      handle     TEXT NOT NULL,
      claim      TEXT NOT NULL DEFAULT '',
      reason     TEXT NOT NULL DEFAULT '',
      branch_id  TEXT NOT NULL DEFAULT '',
      turn       INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL,
      PRIMARY KEY (run_id, handle)
    )"])

(def migrations
  "Ordered. Index 0 is migration 1; PRAGMA user_version holds the count applied."
  [v1 v2 v3 v4 v5 v6])
