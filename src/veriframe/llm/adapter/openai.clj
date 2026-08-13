;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.llm.adapter.openai
  "The OpenAI chat-completions family.

  One adapter covers OpenAI, DeepSeek, Zhipu GLM, and any local llama-server
  or vLLM endpoint, because they all speak the same wire format. The
  differences that actually exist are carried as fields on the adapter record
  rather than as separate namespaces, since a subclass whose only content is a
  base URL is not an abstraction.

  The one real variation is where the reasoning stream lives. DeepSeek and GLM
  return `reasoning_content` alongside `content`; others return nothing. The
  field name is configurable and the client folds it into <think> framing so
  the fence parser sees one string either way."
  (:require [clojure.string :as str]
            [veriframe.llm.adapter :as adapter]))

(defrecord OpenAIAdapter [provider-id label reasoning-key max-tokens-key]
  adapter/Adapter
  (id [_] provider-id)
  (display-name [_] label)

  (chat-url [_ config] (str (:base-url config) "/chat/completions"))
  (models-url [_ config] (str (:base-url config) "/models"))

  (auth-headers [_ config]
    (if-let [k (:api-key config)]
      {"Authorization" (str "Bearer " k)}
      {}))

  (chat-body [_ config {:keys [messages max-tokens temperature]}]
    (cond-> {:model (:model config)
             :messages messages}
      max-tokens (assoc max-tokens-key max-tokens)
      temperature (assoc :temperature temperature)))

  (parse-chat [_ body]
    (when-let [choice (first (:choices body))]
      (let [msg (:message choice)]
        {:content (:content msg)
         :reasoning (get msg reasoning-key)
         :finish-reason (or (:finish_reason choice) "stop")
         :usage (when-let [u (:usage body)]
                  ;; The cache split is conditional on the provider reporting
                  ;; it, and ABSENT rather than zero when it does not: zero
                  ;; would assert every token missed the cache, which is a
                  ;; different and false claim. The point of keeping these is
                  ;; to reason about cache behaviour across a wide beam, where
                  ;; each branch carries its own diverging prefix, and a
                  ;; fabricated zero would poison exactly that question.
                  (cond-> {:prompt-tokens (or (:prompt_tokens u) 0)
                           :completion-tokens (or (:completion_tokens u) 0)
                           :total-tokens (or (:total_tokens u) 0)}
                    (:prompt_cache_hit_tokens u)
                    (assoc :cache-hit-tokens (:prompt_cache_hit_tokens u))
                    (:prompt_cache_miss_tokens u)
                    (assoc :cache-miss-tokens (:prompt_cache_miss_tokens u))
                    ;; OpenAI reports the hit count nested instead.
                    (get-in u [:prompt_tokens_details :cached_tokens])
                    (assoc :cache-hit-tokens
                           (get-in u [:prompt_tokens_details :cached_tokens]))))})))

  (parse-models [_ body] (mapv :id (:data body)))

  (error-message [_ body]
    (when-let [e (:error body)]
      (str (or (:message e) "unknown error")
           (when-let [c (:code e)] (str " (code " c ")")))))

  (usage-cap? [_ _status body]
    ;; A 429 that means "you are out of credit" must not be retried; a 429 that
    ;; means "slow down" must be. Providers signal the first in the error text
    ;; rather than the status, so this is a text match, and it is deliberately
    ;; narrow: misreading a rate limit as a cap costs the run.
    (let [msg (str (get-in body [:error :message])
                   " " (get-in body [:error :type])
                   " " (get-in body [:error :code]))]
      (boolean (re-find #"(?i)insufficient|quota|billing|exceeded your current|payment"
                        msg)))))

(defn openai-family
  "Build an adapter for an OpenAI-compatible endpoint.

  `reasoning-key` names the field carrying a separate reasoning stream, or nil
  when the provider has none. `max-tokens-key` exists because newer OpenAI
  models renamed `max_tokens` to `max_completion_tokens` and reject the old
  one."
  [{:keys [id label reasoning-key max-tokens-key]
    :or {max-tokens-key :max_tokens}}]
  (->OpenAIAdapter id (or label (str/capitalize (name id)))
                   (or reasoning-key :__no_reasoning_field__)
                   max-tokens-key))

(def deepseek
  (openai-family {:id :deepseek :label "DeepSeek" :reasoning-key :reasoning_content}))

(def glm
  (openai-family {:id :glm :label "GLM" :reasoning-key :reasoning_content}))

(def openai
  (openai-family {:id :openai :label "OpenAI"}))

;; A local llama-server / vLLM / LM Studio endpoint. Same wire format, no key.
(def local
  (openai-family {:id :local :label "local"}))
