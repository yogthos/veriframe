;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.api.runs
  "The read model over the journal.

  Every one of these is a query against tables the loop appends to as it goes,
  so they work identically for a live run and a finished one and need no
  cooperation from the loop. That is what makes a UI a client rather than a
  special case.

  The tail endpoint is a cursor over `events` rather than a stream, because a
  cursor works over any HTTP server and a stream does not — see PLAN.md on the
  vendored adapter."
  (:require [clojure.data.json :as json]
            [veriframe.agent.gates :as gates]
            [veriframe.bench.beam :as metrics]
            [veriframe.store.db :as db]
            [veriframe.store.interventions :as interventions]
            [veriframe.store.journal :as journal]
            [veriframe.store.runs :as runs]))

(defn- parse-json [s]
  (when s (try (json/read-str s :key-fn keyword) (catch Throwable _ s))))

(defn list-runs [conn limit]
  {:runs (mapv (fn [r]
                 {:id (:id r) :problem (:problem r) :status (:status r)
                  :model (:model r) :beam_width (:beam_width r)
                  :started_at (:started_at r) :ended_at (:ended_at r)})
               (runs/list-runs conn (or limit 50)))})

(def stall-threshold-ms
  "How long a running run may say nothing before a reader should doubt it.

  Above the 900000ms turn deadline by a factor of two, because silence under
  that is a branch legitimately waiting on a provider call or a Lean tactic
  and reporting it would be crying wolf. Past twice the deadline every branch
  in the beam has had its turn forfeited and the round should have moved on,
  so continued silence means nobody is going to update this row."
  1800000)

(defn- seeded?
  "Whether this run was seeded from a prior one.

  Read off the journal rather than a column: beam/run! applies the seed's
  consequences in memory and the row never learned about it, but the
  `run-seeded` event is written before any branch opens, so the fact is
  already durable."
  [conn run-id]
  (some? (db/fetch-one conn ["SELECT id FROM events
                              WHERE run_id = ? AND kind = 'run-seeded' LIMIT 1"
                             run-id])))

(defn get-run [conn run-id]
  (when-let [r (runs/get-run conn run-id)]
    (let [branches (runs/branches conn run-id)]
      {:run (-> r
              (update :prompt_digest str)
              ;; A status of 'running' is a claim the loop makes once and never
              ;; revisits, so on its own it cannot distinguish a working run
              ;; from a dead one. These two let a client tell.
              (assoc :last_progress_at (runs/last-progress-at conn run-id)
                     :stalled (runs/stalled? conn run-id stall-threshold-ms)
                     ;; beam_width is the repopulation FLOOR — repopulate only
                     ;; fires below it and branch-out grows past it — so the
                     ;; number a caller set is not the number of concurrent
                     ;; provider calls they get. A run started at width 5 was
                     ;; observed at 9 active branches. Report both, and the
                     ;; ceiling that actually bounds it.
                     :active_branches (count (filter #(= "active" (:status %))
                                                     branches))
                     :max_branches (gates/threshold :max-total-branches)
                     ;; Seeding forces sharing on regardless of config
                     ;; (beam.clj), and nothing recorded that, so /health
                     ;; reported the config value while a seeded run shared
                     ;; freely — it once said sharing was off during a run that
                     ;; had served 91 shared artifacts.
                     :share_artifacts (seeded? conn run-id)))
       ;; Reuses the rows already read for the active count above.
       :branches (mapv #(update % :thesis parse-json) branches)
       :metrics (metrics/run-metrics conn run-id)
       :artifacts (mapv #(update % :witness parse-json)
                        (journal/artifacts conn run-id))
       :gates (journal/gate-tally conn run-id)
       :interventions (interventions/history conn run-id)})))

(defn journal-tail
  "Everything after `since`. The `next` cursor is what the client sends back,
  so a poller never has to reason about timestamps or ordering."
  [conn run-id since limit]
  (let [events (journal/events-since conn run-id (or since 0) (or limit 200))]
    {:run_id run-id
     :events (mapv #(update % :data parse-json) events)
     :next (or (:id (last events)) (or since 0))
     :count (count events)}))

(defn branch-detail [conn run-id branch-id]
  (when-let [b (runs/get-branch conn run-id branch-id)]
    {:branch (update b :thesis parse-json)
     :turns (journal/branch-turns conn run-id branch-id)
     :artifacts (mapv #(update % :witness parse-json)
                      (journal/artifacts conn run-id branch-id))}))
