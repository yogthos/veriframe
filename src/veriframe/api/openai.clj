;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.api.openai
  "The OpenAI-compatible surface.

  A caller pointing an OpenAI client at this gets a chat completion whose
  content is the verified answer. The harness trace rides along in a
  non-standard `harness` field, which clients ignore and humans want.

  `raw: true` bypasses the loop entirely and forwards to the provider. That is
  the control arm: the same question, same model, no verification, and it is
  worth keeping precisely because it is the comparison anyone will ask for."
  (:require [clojure.string :as str]
            [veriframe.agent.beam :as beam]
            [veriframe.bench.beam :as metrics]
            [veriframe.llm.client :as llm]
            [veriframe.llm.registry :as registry]
            [veriframe.store.journal :as journal]))

(defn- last-user-content [messages]
  (->> messages
       (filter #(= "user" (or (:role %) (get % "role"))))
       last
       (#(or (:content %) (get % "content")))))

(defn- completion-envelope [model content extra]
  (merge
   {:id (str "chatcmpl-" (random-uuid))
    :object "chat.completion"
    :created (quot (System/currentTimeMillis) 1000)
    :model model
    :choices [{:index 0
               :message {:role "assistant" :content content}
               :finish_reason "stop"}]}
   extra))

(defn- render-answer
  "What the caller sees. The answer, then the evidence that earned it, because
  an unverifiable answer from a verification harness is worth less than one
  whose artifacts are listed."
  [answer artifacts]
  (let [confirmed (filter #(= "confirmed" (:claim_status %)) artifacts)]
    (str answer
         (when (seq confirmed)
           (str "\n\n---\n\nVerified along the way:\n"
                (str/join "\n" (for [a confirmed]
                                 (str "- [" (:kind a) "/" (:tier a) "] " (:claim a)))))))))

(defn chat-completion
  "Run the harness on the last user message and answer in OpenAI's shape."
  [{:keys [conn config]} body]
  (let [llm-config (:llm config)
        adapter (registry/adapter-for (:provider llm-config))
        model (:model llm-config)
        messages (or (:messages body) (get body "messages"))
        problem (last-user-content messages)]
    (cond
      (str/blank? problem)
      {:status 400
       :body {:error {:message "no user message in `messages`" :type "invalid_request_error"}}}

      ;; The bypass. Same model, same question, no verification.
      (or (:raw body) (get body "raw"))
      (let [r (llm/chat adapter llm-config messages)]
        {:status 200
         :body (completion-envelope model (:content r)
                                    {:usage (:usage r) :harness {:mode "raw"}})})

      :else
      (let [r (beam/run! {:conn conn :config config
                          :llm-adapter adapter :llm-config llm-config
                          :problem problem
                          :max-turns (or (:max_turns body) (:max-turns body)
                                         (get-in config [:run :max-turns]))
                          :beam-width (or (:beam_width body) (:beam-width body)
                                          (get-in config [:run :beam-width]))})
            artifacts (journal/artifacts conn (:run-id r))
            answered (= :completed (:status r))]
        {:status 200
         :body (completion-envelope
                model
                (if answered
                  (render-answer (:answer r) artifacts)
                  ;; An exhausted run ships its progress report, not a failure
                  ;; string: never ship nothing, never ship a lie. Other
                  ;; unanswered statuses (abort, error) keep the plain line.
                  (or (:report-text r)
                      (str "The harness did not reach a verified answer ("
                           (name (:status r)) ").\n\n"
                           (or (beam/summary r) ""))))
                {:harness {:mode "agent"
                           :run_id (:run-id r)
                           :status (name (:status r))
                           :report (:report r)
                           :metrics (metrics/run-metrics conn (:run-id r))
                           :branches (mapv (fn [b]
                                             {:id (:id b) :status (name (:status b))
                                              :confirmed (count (filter #(= :confirmed (:claim-status %))
                                                                        (:artifacts b)))})
                                           (:branches r))}})}))))
