;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU General Public License as
;; published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public
;; License along with this program. If not, see
;; <https://www.gnu.org/licenses/>.

(ns veriframe.api.control
  "Starting runs, intervening in them, and stopping them.

  Two paths on purpose. A directive goes on a queue and is drained at the next
  branch boundary, because a branch mid provider-call is not something to
  mutate. An abort goes straight to the supervisor, because a wedged run is
  exactly the one that will never reach another boundary — that is the RAX
  manager pattern, and it is why the stop path does not share machinery with
  the steer path."
  (:require [clojure.tools.logging :as log]
            [veriframe.agent.beam :as beam]
            [veriframe.llm.registry :as registry]
            [veriframe.store.interventions :as interventions]
            [veriframe.store.runs :as runs]))

;; run-id -> {:future f :abort (atom false)}. A run outlives the request that
;; started it, so something has to hold it.
(defonce active (atom {}))

(defn start-run!
  "Kick off a run in the background and return its id immediately.

  `POST /v1/chat/completions` blocks on the same machinery for OpenAI
  compatibility; this is the path for everything else."
  ;; JSON bodies arrive with underscored keys; accept both so a caller is
  ;; never silently given the config default when they asked for something
  ;; specific. The first API call made here asked for beam_width 2 and got 5.
  [{:keys [conn config]} body]
  (let [problem (or (:problem body) (get body "problem"))
        max-turns (or (:max_turns body) (:max-turns body))
        beam-width (or (:beam_width body) (:beam-width body))]
  (let [llm-config (:llm config)
        adapter (registry/adapter-for (:provider llm-config))
        abort (atom false)
        promised (promise)
        fut (future
              (try
                (let [r (beam/run! {:conn conn :config config
                                    :llm-adapter adapter :llm-config llm-config
                                    :problem problem
                                    :max-turns max-turns
                                    :beam-width beam-width
                                    :abort abort
                                    :on-start #(deliver promised %)})]
                  (swap! active dissoc (:run-id r))
                  r)
                (catch Throwable e
                  (log/error "run failed:" (ex-message e))
                  {:status :error :error (ex-message e)})))
        run-id (deref promised 30000 nil)]
    (if run-id
      (do (swap! active assoc run-id {:future fut :abort abort})
          {:run_id run-id :status "running"
           :beam_width (or beam-width (get-in config [:run :beam-width]))
           :max_turns (or max-turns (get-in config [:run :max-turns]))})
      {:error "the run did not start within 30s"}))))

(defn abort!
  "Stop a run without asking it to cooperate. The flag is checked at the top of
  every scheduling round, and the run's finally block disposes every engine
  session regardless of how it ended."
  [conn run-id]
  (if-let [{:keys [abort]} (get @active run-id)]
    (do (reset! abort true)
        (runs/finish-run! conn run-id :aborted nil)
        {:run_id run-id :status "aborting"})
    {:error (str "no active run " run-id)}))

(defn intervene!
  [conn run-id body]
  (let [id (interventions/submit! conn run-id
                                  {:branch-id (:branch_id body)
                                   :kind (:kind body)
                                   :payload (:payload body)
                                   :issued-by (or (:issued_by body) "human")})]
    {:id id
     :status "pending"
     ;; Said plainly rather than implied, because the difference between
     ;; accepted and applied is the thing a UI most easily lies about.
     :note "Queued. It applies at the branch's next turn boundary, not now."}))

(defn kinds [] {:kinds interventions/kinds})
