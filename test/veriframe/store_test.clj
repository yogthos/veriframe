;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.store-test
  "Phase 0 storage, kept as tests rather than only as smoke probes.

  The migration statement-count check is the one that matters. db.sqlite/query
  calls sqlite3_prepare_v2 with a null tail pointer, so a migration written as
  one multi-statement string would execute only its first statement and report
  no error. Counting objects created against statements written is how that
  failure mode stays loud."
  (:require [clojure.test :refer [deftest testing is]]
            [jdbc.core :as jdbc]
            [veriframe.store.db :as db]
            [veriframe.store.journal :as journal]
            [veriframe.store.migrations :as migrations]
            [veriframe.store.runs :as runs]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(deftest migrations-apply
  (with-db [c]
    (is (= (count migrations/migrations) (db/schema-version c)))
    (is (every? (set (db/table-names c))
                ["runs" "branches" "turns" "artifacts" "failures"
                 "gate_firings" "interventions" "events"
                 "shared_artifacts"]))))

(deftest migrations-are-idempotent
  (with-db [c]
    (let [v (db/schema-version c)
          tables (db/table-names c)]
      (db/migrate! c)
      (is (= v (db/schema-version c)))
      (is (= tables (db/table-names c))))))

(deftest every-migration-statement-runs
  (testing "no migration hides statements behind a multi-statement string"
    ;; Every entry must be a vector of single statements. A statement holding
    ;; a second one after a semicolon would be silently dropped by
    ;; sqlite3_prepare_v2, so reject that shape outright rather than trusting
    ;; that nobody writes it.
    (doseq [[i statements] (map-indexed vector migrations/migrations)]
      (is (vector? statements) (str "migration v" (inc i) " must be a vector"))
      (doseq [sql statements]
        (is (not (re-find #";\s*\S" (clojure.string/replace sql #"--[^\n]*" "")))
            (str "migration v" (inc i) " has a statement containing a `;` followed by"
                 " more SQL, which sqlite3_prepare_v2 would silently drop:\n" sql))))))

(deftest fts5-is-available-through-the-ffi-binding
  ;; Distinct from the sqlite3 CLI having FTS5. The failure mode is a
  ;; migration that throws at startup.
  (let [c (db/connect ":memory:")]
    (try
      (is (db/fts5-available? c))
      (finally (db/close c)))))

(deftest failures-fts-round-trips
  (with-db [c]
    (jdbc/execute! c ["INSERT INTO failures_fts (claim, reason) VALUES (?, ?)"
                      "sidon set of size 24 exists" "z3 returned unsat"])
    (jdbc/execute! c ["INSERT INTO failures_fts (claim, reason) VALUES (?, ?)"
                      "the coloring is schur-good" "monochromatic triple at 3+3=6"])
    (is (= 1 (count (jdbc/fetch c ["SELECT claim FROM failures_fts WHERE failures_fts MATCH ?"
                                   "sidon"]))))
    (is (= 1 (count (jdbc/fetch c ["SELECT claim FROM failures_fts WHERE failures_fts MATCH ?"
                                   "monochromatic"]))))
    (is (empty? (jdbc/fetch c ["SELECT claim FROM failures_fts WHERE failures_fts MATCH ?"
                               "lean"])))))

(deftest a-turn-keeps-what-the-model-said
  ;; v1 stored the tool call and the result but not the prose, so a turn that
  ;; produced no tool call recorded only that fact. Nine of twenty turns in a
  ;; Lean run came back __no_call__ and the question "why" had no answer in the
  ;; data, because the one artefact that would settle it was the one thing not
  ;; kept. This asserts the no-call path in particular, since that is the path
  ;; where every other column is empty.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :beam-width 1})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (journal/record-turn! c rid
                          {:branch-id "B1" :turn 1 :tool-name "__no_call__"
                           :result "[harness] No tool-call block." :category "failure"
                           :assistant-text "I think the answer is 4. Let me explain at length."
                           :reasoning-text "the model's private reasoning"})
    (let [t (first (journal/turns c rid))]
      (is (= "__no_call__" (:tool_name t)))
      (is (= "I think the answer is 4. Let me explain at length." (:assistant_text t)))
      (is (= "the model's private reasoning" (:reasoning_text t)))))))

(deftest a-turn-without-a-response-stores-null-rather-than-the-string-null
  ;; The provider-error path has no response at all. Coercing that to "" or
  ;; "null" would make "the model said nothing" indistinguishable from "the
  ;; model was never asked".
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :beam-width 1})]
    (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
    (journal/record-turn! c rid
                          {:branch-id "B1" :turn 1 :tool-name "__provider_error__"
                           :result "timeout" :category "neutral"})
    (is (nil? (:assistant_text (first (journal/turns c rid))))))))

(deftest branch-turns-reads-one-branch-and-leaves-the-bulk-behind
  ;; The GUI's branch panel hung forever on gen-14. branch-detail called
  ;; journal/turns, which selects EVERY turn of the whole run with every
  ;; column and then filters for one branch in Clojure — so opening one
  ;; branch fetched 5.5MB of assistant_text that the panel never renders,
  ;; took over two minutes, and blew the client's 45s socket timeout. The
  ;; run's index on (run_id, branch_id, turn) was sitting unused.
  ;;
  ;; assistant_text and reasoning_text stay out of this projection on
  ;; purpose. They are the bulk, nothing displaying a branch wants them, and
  ;; resume — which does — keeps using journal/turns.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :beam-width 1})]
      (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
      (runs/open-branch! c rid {:branch-id "B2" :created-at-turn 0})
      (journal/record-turn! c rid {:branch-id "B1" :turn 1 :tool-name "verify"
                                   :result "ok" :category "success"
                                   :assistant-text "a very long reply"
                                   :reasoning-text "private reasoning"})
      (journal/record-turn! c rid {:branch-id "B2" :turn 1 :tool-name "thesis"
                                   :result "registered" :category "neutral"
                                   :assistant-text "another long reply"})
      (let [ts (journal/branch-turns c rid "B1")
            t (first ts)]
        (is (= 1 (count ts)) "only the branch asked for")
        (is (= "verify" (:tool_name t)))
        (is (= "ok" (:result t)) "the result is what gets rendered, so it stays")
        (is (not (contains? t :assistant_text))
            "the bulk column is not in the projection at all")
        (is (not (contains? t :reasoning_text))))
      (is (= 1 (count (journal/branch-turns c rid "B2"))))
      (is (empty? (journal/branch-turns c rid "nosuch")))
      ;; resume still needs the full row, so the old accessor keeps its shape.
      (is (= "a very long reply"
             (:assistant_text (first (journal/turns c rid))))))))

;; --- liveness ---------------------------------------------------------------

(deftest last-progress-tracks-the-newest-journal-entry
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :beam-width 1})]
      ;; start-run! journals :run-started, so a fresh run already has progress.
      (is (some? (runs/last-progress-at c rid)))
      (journal/note! c rid :turn {:data {:n 1}})
      (is (some? (runs/last-progress-at c rid))))
    (is (nil? (runs/last-progress-at c "no-such-run"))
        "a run with no events has no progress timestamp rather than a fake one")))

(deftest a-run-is-stalled-only-when-it-is-running-and-quiet
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :beam-width 1})
          ;; The run's only event is :run-started, which we age by choosing a
          ;; threshold shorter than it has existed rather than by editing rows.
          long-window 3600000]
      (is (false? (runs/stalled? c rid long-window))
          "a run that just emitted an event is not stalled")
      (is (true? (runs/stalled? c rid -1))
          "past the threshold with no newer event, a running run is stalled")
      (runs/finish-run! c rid :completed "answer")
      (is (false? (runs/stalled? c rid -1))
          "a finished run is quiet because it is over, not because it is stuck"))))

(deftest a-run-left-running-by-a-crash-is-reconciled-at-startup
  ;; status='running' is a claim the beam makes once and never revisits, so a
  ;; process that dies mid-run leaves the row asserting forever. gen-18 and
  ;; gen-11 both sat that way; the second was filed as a separate bug before
  ;; anyone noticed it was the same defect, which is the argument for fixing
  ;; the mechanism rather than the rows.
  ;;
  ;; Nothing in-process can distinguish "running" from "was running when we
  ;; died" — but nothing can be running at STARTUP, because the beam only ever
  ;; runs in this process. So the reconciliation is sound exactly here and
  ;; nowhere else.
  (with-db [c]
    (let [crashed (runs/start-run! c {:problem "died" :beam-width 1})
          done    (runs/start-run! c {:problem "finished" :beam-width 1})]
      (runs/finish-run! c done :completed "answer")
      (is (= 1 (runs/reconcile-orphans! c))
          "exactly the one row still claiming to run")
      (let [r (runs/get-run c crashed)]
        (is (= "interrupted" (:status r))
            "a run nobody is running is interrupted, not completed or failed —
             it neither finished nor errored, and saying either would be a lie")
        (is (some? (:ended_at r))
            "an ended run needs an end time or every duration is wrong"))
      (is (= "completed" (:status (runs/get-run c done)))
          "a finished run is left alone")
      (is (zero? (runs/reconcile-orphans! c))
          "idempotent: a second startup has nothing to reconcile"))))
