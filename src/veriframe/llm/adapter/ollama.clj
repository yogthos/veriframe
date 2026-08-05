;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.llm.adapter.ollama
  "Ollama's native /api/chat.

  Ollama also exposes an OpenAI-compatible surface at /v1, and pointing the
  openai adapter at it works. This adapter targets the native API instead,
  because that is where Ollama surfaces the things the harness cares about:
  `think` on models that support it, `num_predict` and `num_ctx` under
  `options`, and a `done_reason` that distinguishes hitting the token cap from
  stopping naturally.

  The reply is a single object rather than a choices array, and `stream` has to
  be false explicitly or the body arrives as newline-delimited JSON."
  (:require [veriframe.llm.adapter :as adapter]))

(defrecord OllamaAdapter []
  adapter/Adapter
  (id [_] :ollama)
  (display-name [_] "Ollama")

  (chat-url [_ config] (str (:base-url config) "/api/chat"))
  (models-url [_ config] (str (:base-url config) "/api/tags"))

  (auth-headers [_ config]
    ;; Local Ollama needs none; a proxied or hosted one may.
    (if-let [k (:api-key config)] {"Authorization" (str "Bearer " k)} {}))

  (chat-body [_ config {:keys [messages max-tokens temperature]}]
    (cond-> {:model (:model config)
             :messages messages
             ;; Without this the body is newline-delimited JSON, not one object.
             :stream false}
      (or max-tokens temperature)
      (assoc :options (cond-> {}
                        max-tokens (assoc :num_predict max-tokens)
                        temperature (assoc :temperature temperature)))))

  (parse-chat [_ body]
    (when-let [msg (:message body)]
      {:content (:content msg)
       ;; Ollama returns a separate thinking stream on models that emit one.
       :reasoning (:thinking msg)
       :finish-reason (or (:done_reason body) "stop")
       :usage (when (:eval_count body)
                {:prompt-tokens (or (:prompt_eval_count body) 0)
                 :completion-tokens (or (:eval_count body) 0)
                 :total-tokens (+ (or (:prompt_eval_count body) 0)
                                  (or (:eval_count body) 0))})}))

  (parse-models [_ body] (mapv :name (:models body)))

  (error-message [_ body]
    (when-let [e (:error body)] (str e)))

  ;; A local runtime has no billing wall to hit.
  (usage-cap? [_ _ _] false))

(def ollama (->OllamaAdapter))
