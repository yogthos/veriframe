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
            [veriframe.bench.beam :as metrics]
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

(defn get-run [conn run-id]
  (when-let [r (runs/get-run conn run-id)]
    {:run (-> r (update :prompt_digest str))
     :branches (mapv #(update % :thesis parse-json) (runs/branches conn run-id))
     :metrics (metrics/run-metrics conn run-id)
     :artifacts (mapv #(update % :witness parse-json)
                      (journal/artifacts conn run-id))
     :gates (journal/gate-tally conn run-id)
     :interventions (interventions/history conn run-id)}))

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
     :turns (filterv #(= branch-id (:branch_id %)) (journal/turns conn run-id))
     :artifacts (mapv #(update % :witness parse-json)
                      (journal/artifacts conn run-id branch-id))}))
