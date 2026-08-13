;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

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
            [veriframe.agent.resume :as resume]
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
        beam-width (or (:beam_width body) (:beam-width body))
        seed-run (or (:seed_run body) (:seed-run body))]
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
                                    :seed-run seed-run
                                    :abort abort
                                    :on-start #(deliver promised %)})]
                  (swap! active dissoc (:run-id r))
                  r)
                (catch Throwable e
                  (log/error "run failed:" (ex-message e))
                  ;; beam/run! has already marked the row failed and journaled
                  ;; the error; this only drops the in-memory handle, which is
                  ;; otherwise leaked and leaves abort! reporting a dead run as
                  ;; abortable. deref with 0 because by here the id has long
                  ;; been delivered — unless the throw beat on-start, in which
                  ;; case there is no id to forget.
                  (when-let [rid (deref promised 0 nil)]
                    (swap! active dissoc rid))
                  {:status :error :error (ex-message e)})))
        run-id (deref promised 30000 nil)]
    (if run-id
      (do (swap! active assoc run-id {:future fut :abort abort})
          ;; Wrapped in :body like resume, so one route shape serves both the
          ;; success and the refusal and neither has to be special-cased.
          {:body {:run_id run-id :status "running"
                  :beam_width (or beam-width (get-in config [:run :beam-width]))
                  :max_turns (or max-turns (get-in config [:run :max-turns]))}})
      ;; 503, not 200: the request was well formed and the server could not
      ;; service it. Answering 200 with an error body made a caller that checks
      ;; the status code read this as a started run, which is why gui.api's
      ;; start-run! had to unwrap the body to find out otherwise.
      {:status 503
       :body {:error {:message "the run did not start within 30s"}}}))))

(defn abort!
  "Stop a run without asking it to cooperate. The flag is checked at the top of
  every scheduling round, and the run's finally block disposes every engine
  session regardless of how it ended."
  [conn run-id]
  (if-let [{:keys [abort]} (get @active run-id)]
    (do (reset! abort true)
        (runs/finish-run! conn run-id :aborted nil)
        ;; :body, not a bare map: the run's own :status is the string
        ;; "aborting", and a route reading (:status r) as an HTTP code would
        ;; have sent that.
        {:body {:run_id run-id :status "aborting"}})
    ;; 409, matching resume's "not resumable": the run may well exist, it is
    ;; just not in a state that can be aborted. This answered 200 with an error
    ;; body, so a caller reading the status code alone saw a refusal as a
    ;; successful abort.
    {:status 409
     :body {:error {:message (str "no active run " run-id)}
            :run_id run-id}}))

(defn resume!
  "Resume a crashed run from its journal, in the background like start-run!.

  Returns {:status 409 :body ...} when the run is not resumable — aborted runs
  stay aborted, completed runs shipped — else a success map the caller turns
  into an HTTP 200. The resumed run is registered under `active` with a fresh
  abort flag, so abort! can stop it like any other.

  `body` may carry max_turns: an explicit budget extension that reopens
  branches closed as exhausted. Omitted, the original budget stands."
  [{:keys [conn config]} run-id body]
  (if-not (resume/resumable? conn run-id)
    {:status 409 :body {:error {:message (str "run " run-id " is not resumable")
                                :run_id run-id}}}
    (let [llm-config (:llm config)
          adapter (registry/adapter-for (:provider llm-config))
          abort (atom false)
          max-turns (or (:max_turns body) (:max-turns body))
          fut (future
                (try
                  (let [r (resume/resume! {:conn conn :config config
                                           :llm-adapter adapter
                                           :llm-config llm-config
                                           :run-id run-id :abort abort
                                           :max-turns max-turns})]
                    (swap! active dissoc run-id)
                    r)
                  (catch Throwable e
                    (log/error "resume failed:" (ex-message e))
                    {:status :error :error (ex-message e)})))]
      (swap! active assoc run-id {:future fut :abort abort})
      {:body {:run_id run-id :status "resuming"
              :max_turns (:max_turns (runs/get-run conn run-id))}})))
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
