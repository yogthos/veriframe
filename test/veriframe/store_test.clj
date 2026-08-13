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
            [veriframe.agent.gates :as gates]
            [veriframe.agent.loop :as branch-loop]
            [veriframe.agent.state :as state]
            [veriframe.api.runs :as api-runs]
            [veriframe.store.artifacts :as artifacts]
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

(deftest a-turn-records-what-it-cost
  ;; The adapter parsed usage and client/chat threaded it through, and the
  ;; agent loop dropped it on the floor: nothing outside the bench harness and
  ;; the raw passthrough API ever read :usage, and `turns` had no token
  ;; columns. So the harness could not answer what a run cost — for a system
  ;; whose whole operating rule is that a generation is hours of provider
  ;; spend, that is the one number it should never have been missing.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (journal/record-turn! c rid
                            {:branch-id "B1" :turn 1 :tool-name "verify"
                             :result "ok" :category :success
                             :usage {:prompt-tokens 100 :completion-tokens 20
                                     :total-tokens 120
                                     :cache-hit-tokens 80 :cache-miss-tokens 20}})
      (let [t (first (journal/turns c rid))]
        (is (= 100 (:prompt_tokens t)))
        (is (= 20 (:completion_tokens t)))
        (is (= 80 (:cache_hit_tokens t)))
        (is (= 20 (:cache_miss_tokens t)))))
    (testing "a turn with no usage stores nulls, not zeros"
      ;; The provider-error path has no response and therefore no usage. A
      ;; zero there would be a claim that the call was free, and summing it
      ;; would under-report the run's cost rather than admit ignorance.
      (let [rid (runs/start-run! c {:problem "p2"})]
        (journal/record-turn! c rid
                              {:branch-id "B1" :turn 1
                               :tool-name "__provider_error__"
                               :result "boom" :category :neutral})
        (let [t (first (journal/turns c rid))]
          (is (nil? (:prompt_tokens t)))
          (is (nil? (:cache_hit_tokens t))))))))

(deftest a-run-reports-the-width-it-is-actually-running-at
  ;; beam_width is the repopulation FLOOR, not a cap: repopulate only fires
  ;; below it, and branch-out grows past it up to :max-total-branches, which is
  ;; 15. A run started at width 5 was observed carrying 9 active branches — 9
  ;; concurrent provider calls per round and 9 engine processes, from a
  ;; parameter that reads like a ceiling.
  ;;
  ;; The number is knowable from the branches table, so report it rather than
  ;; leaving whoever set beam_width to discover the multiplier from `ps`.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :beam-width 2})]
      (doseq [b ["B1" "B2" "B3"]]
        (runs/open-branch! c rid {:branch-id b}))
      (runs/close-branch! c rid "B3" :culled "dominated")
      (let [d (api-runs/get-run c rid)]
        (is (= 2 (get-in d [:run :beam_width]))
            "the requested width is still reported, unchanged")
        (is (= 2 (get-in d [:run :active_branches]))
            "alongside how many branches are actually running")
        (is (= (gates/threshold :max-total-branches)
               (get-in d [:run :max_branches]))
            "and the ceiling that actually bounds it")))))

(deftest a-seeded-run-reports-that-sharing-is-on
  ;; beam/run! forces :share-artifacts? true for any run started with a
  ;; seed-run, in memory, overriding config. Nothing recorded it, so /health
  ;; reported the CONFIG value — during one run it said sharing was off while
  ;; that run had fired 91 shared-artifact-hit events. A run-level fact
  ;; reported as a global one is how a later investigation starts from a false
  ;; premise; this one cost a detour before the contradiction resolved.
  ;;
  ;; Derived from the run-seeded event rather than a new column: the journal
  ;; already records the fact, so the row does not need to.
  (with-db [c]
    (let [plain (runs/start-run! c {:problem "p"})
          seeded (runs/start-run! c {:problem "q"})]
      (journal/note! c seeded :run-seeded {:data {:source "prior" :artifacts 3}})
      (is (true? (get-in (api-runs/get-run c seeded) [:run :share_artifacts]))
          "a seeded run shares regardless of config")
      (is (false? (get-in (api-runs/get-run c plain) [:run :share_artifacts]))
          "an unseeded one does not claim to"))))

(deftest the-shared-block-carries-the-code-not-just-the-claim
  ;; Three gen-20 branches proved the same scalarization lemma — B4 as Hf/Hg,
  ;; B3 as H/K, B4.2 as alpha/beta — while being shown each other's claims five
  ;; times each. They were not failing to discover it. `render` printed the
  ;; claim text alone, and a branch cannot cite a theorem statement it has
  ;; never seen, so re-deriving was the only move available to it.
  ;;
  ;; The code was there the whole time: seed-from-run! copies it precisely so
  ;; an inherited lemma can be re-confirmed in one cheap turn, and both
  ;; `similar` and `recent` select it. Only the renderer dropped it.
  (let [block (artifacts/render
               [{:branch_id "B3" :kind "lean" :tier "slow"
                 :claim "Scalarization yields lexicographic minimizer"
                 :code "theorem scalarization_yields_lex_min : True := trivial"}])]
    (is (re-find #"Scalarization yields" block))
    (is (re-find #"scalarization_yields_lex_min" block)
        "the statement a sibling would need to build on must be in the block")
    (testing "an artifact with no code still renders"
      (is (re-find #"bare claim"
                   (artifacts/render [{:branch_id "B1" :kind "prolog"
                                       :tier "fast" :claim "bare claim"}]))))))

(deftest in-run-artifacts-outrank-seeded-ones-in-the-block
  ;; gen-20 served 91 shared artifacts, 67 of them seeded from gen-19 and only
  ;; 24 produced by the run itself. A completed run contributes a full pool at
  ;; turn 0 while the live run's starts empty, so seeds win on volume from the
  ;; start and keep winning.
  ;;
  ;; Ranked rather than dropped: a campaign that seeds without restating the
  ;; prior results in its problem statement would lose them entirely, and
  ;; seed-from-run! exists for exactly that case.
  (let [entries [{:branch_id "seed:B1" :claim "old a"} {:branch_id "seed:B2" :claim "old b"}
                 {:branch_id "seed:B3" :claim "old c"} {:branch_id "B4" :claim "new a"}
                 {:branch_id "B5" :claim "new b"}]
        ranked (artifacts/prefer-in-run entries)]
    (is (= ["B4" "B5" "seed:B1" "seed:B2" "seed:B3"] (mapv :branch_id ranked))
        "everything the run produced itself comes first")
    (is (= (set entries) (set ranked)) "and nothing is dropped")
    (testing "order within each group is preserved — relevance ranking survives"
      (is (= ["new a" "new b"] (mapv :claim (take 2 ranked)))))))

(deftest an-in-run-lemma-reaches-the-block-even-when-seeds-dominate
  ;; The half of the fix that ranking alone does not give. context-block used
  ;; to fetch exactly the number it displays, so when the top 5 by relevance
  ;; were all seeds — which is the normal case early, since a completed run
  ;; contributes its whole pool at turn 0 — reordering them just reordered
  ;; seeds. The pool has to be fetched wider than it is shown.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (doseq [i (range 10)]
        (artifacts/record! c rid {:branch-id (str "seed:B" i) :turn 1 :kind :lean
                                  :tier :slow
                                  :claim (str "scalarization lemma variant " i)
                                  :code (str "theorem seeded_" i " : True := trivial")}))
      (artifacts/record! c rid {:branch-id "B3" :turn 9 :kind :lean :tier :slow
                                :claim "scalarization lemma proved in this run"
                                :code "theorem in_run_scalarization : True := trivial"})
      (let [{:keys [block]} (#'branch-loop/context-block
                             c rid (state/new-branch {:id "B7" :problem "p"})
                             "scalarization lemma" true)]
        (is (re-find #"in_run_scalarization" block)
            "the run's own lemma must reach the block, not be buried under seeds")
        (is (re-find #"proved in this run" block))
        ;; And it leads, so it is read first.
        (is (< (.indexOf block "proved in this run")
               (.indexOf block "variant"))
            "in-run entries come before seeded ones")))))

(deftest the-ledger-carries-what-was-ruled-out-not-only-what-was-proved
  ;; 127 refuted artifacts exist across the project and not one had ever been
  ;; shared: loop/shareable? admits only :confirmed, so a branch was told what
  ;; siblings had PROVEN and never what they had DISPROVEN. A refutation is
  ;; worth more per token than a confirmation for choosing what to try — a
  ;; confirmation adds a fact, a refutation prunes a direction.
  ;;
  ;; Read from `artifacts` rather than `shared_artifacts`, which has no
  ;; claim_status column: the authoritative table already carries everything,
  ;; so this needs no migration and cannot drift from the record.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})
          art (fn [bid status claim]
                (journal/record-artifact!
                 c rid {:branch-id bid :turn 1 :kind :lean :tier :slow
                        :claim claim :code (str "theorem t_" claim)
                        :claim-status status}))]
      (art "B1" :confirmed "scalarization yields the lex minimum")
      (art "B2" :refuted "zero total signed weight preserves optimality")
      (art "B3" :unfaithful "sat is offered for a universal claim")
      (art "B4" :empirical "tie sizes on 60 random 3x3 grids")
      (let [led (journal/ledger c rid)
            established (mapv :claim (:established led))
            ruled-out (mapv :claim (:ruled-out led))]
        (is (= ["scalarization yields the lex minimum"] established))
        (is (= ["zero total signed weight preserves optimality"] ruled-out)
            "the disproven half, which nothing carried before")
        (testing "unfaithful stays out — the encoding never established the claim"
          ;; 110 of these exist. Sharing one spreads an assertion that nothing
          ;; verified, which is the opposite of what a ledger is for.
          (is (not-any? #(re-find #"sat is offered" %)
                        (concat established ruled-out))))
        (testing "every row carries an id, so the encoding can be fetched"
          (is (every? :id (concat (:established led) (:ruled-out led)))))))))

(deftest the-ledger-includes-the-branch-s-own-work-and-stays-small
  ;; Unlike the shared block, which excludes own-branch rows because a branch
  ;; re-reading its own lemmas mid-narrative is noise. As a LIST it is the
  ;; point: the alternative is scanning an 80-turn transcript for what you
  ;; established. gen-20's entire confirmed knowledge is 1,495 chars — under
  ;; 400 tokens — against B3's ~50k-char transcript.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p"})]
      (doseq [i (range 3)]
        (journal/record-artifact! c rid {:branch-id "B1" :turn i :kind :smt
                                         :tier :fast :claim (str "own fact " i)
                                         :code "(assert true)"
                                         :claim-status :confirmed}))
      (let [led (journal/ledger c rid)]
        (is (= 3 (count (:established led))) "own artifacts are in the ledger")
        (let [block (artifacts/render-ledger led)]
          (is (re-find #"own fact 0" block))
          (is (re-find #"(?i)established" block))
          (testing "an empty ledger renders nothing rather than an empty heading"
            (is (nil? (artifacts/render-ledger {:established [] :ruled-out []})))))))))

(deftest the-ledger-renders-refuted-so-it-cannot-be-read-as-proved
  ;; A refutation formatted like a confirmation is worse than not sharing it.
  ;; Separate sections rather than a status tag on a line, so a model skimming
  ;; the block cannot merge the two.
  (let [block (artifacts/render-ledger
               {:established [{:id 1 :branch_id "B1" :kind "lean" :tier "slow"
                               :claim "the rule is well defined"}]
                :ruled-out [{:id 2 :branch_id "B2" :kind "smt" :tier "fast"
                             :claim "the natural strengthening holds"}]})
        est-at (.indexOf block "the rule is well defined")
        ref-at (.indexOf block "the natural strengthening holds")]
    (is (pos? est-at))
    (is (pos? ref-at))
    (is (re-find #"(?i)ruled out|disproven|refuted" block)
        "the disproven section must be labelled as such")
    (is (< est-at ref-at) "established first, then what is closed")
    (testing "the id is present and usable as a handle"
      (is (re-find #"a#2" block)))))

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

(deftest resuming-a-run-puts-it-back-into-running
  ;; A resumed run is running, and the row has to say so. Before crashed runs
  ;; were reconciled the row still read 'running' from the original start, so
  ;; nothing had to set it and nothing did; reconciling to 'interrupted' made
  ;; that omission visible — gen-19 resumed, took turns, and its row still
  ;; said interrupted.
  ;;
  ;; It is not cosmetic. stalled? only reports on a run whose status is
  ;; 'running', so a resumed run that wedges is invisible to the one check
  ;; built to notice exactly that. ended_at has to go too, or the run has an
  ;; end time that precedes half its turns.
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :beam-width 1})]
      (runs/reconcile-orphans! c)
      (is (= "interrupted" (:status (runs/get-run c rid))))
      (runs/mark-running! c rid)
      (let [r (runs/get-run c rid)]
        (is (= "running" (:status r)))
        (is (nil? (:ended_at r)) "a running run has not ended")))))
