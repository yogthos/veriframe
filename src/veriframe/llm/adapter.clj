;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.llm.adapter
  "The provider adapter protocol.

  Everything that is the same for every provider — the retry ladder, the
  wall-clock bound, think-block handling, message normalization, JSON framing —
  lives in veriframe.llm.client. An adapter carries only the deltas: where the
  endpoint is, how it authenticates, what the request body is called, and where
  the content and the reasoning live in the reply.

  Keeping the protocol this small is deliberate. Every method an adapter is
  allowed to override is a place where a provider can quietly diverge from the
  retry and timeout discipline, and those are exactly the behaviours that were
  worth centralizing."
  (:refer-clojure :exclude [name]))

(defprotocol Adapter
  (id [this]
    "Keyword identifying the provider, e.g. :deepseek.")

  (display-name [this]
    "Human-readable name, used in error messages.")

  (chat-url [this config]
    "Full URL for a chat completion.")

  (models-url [this config]
    "Full URL for listing models, or nil if the provider has no such endpoint.")

  (auth-headers [this config]
    "Provider-specific auth headers as a string->string map.")

  (chat-body [this config request]
    "Build the request body map from a normalized request:
     {:messages [{:role :content}] :max-tokens n :temperature d :prefill s}.
     Returned as data; the client encodes it.

     `:prefill` is honoured only when `prefill-support?` is true, and MUST be
     ignored otherwise — a provider without support has to produce exactly the
     body it produces today.")

  (prefill-support? [this]
    "Whether this provider will continue a trailing assistant message rather
     than treating it as a completed turn.

     Tool calls here are a fenced JSON block in free text, so the model can
     always answer in prose instead — the harness's dominant mechanical
     failure. Sending the opening fence as a partial assistant turn removes
     that option, but only where the provider continues it. Declared per
     provider rather than attempted and hoped for, because the failure is
     silent: an ignored prefill just looks like an ordinary turn.")

  (parse-chat [this body]
    "Pull {:content :reasoning :finish-reason :usage} out of a decoded
     successful reply. Return nil for a reply that carries no completion, and
     the client will raise with the provider's name attached.")

  (parse-models [this body]
    "Model ids from a decoded models reply.")

  (error-message [this body]
    "A provider-specific error string from a decoded error reply, or nil.")

  (usage-cap? [this status body]
    "Whether a 429 is a hard usage cap rather than a rate limit. A cap must
     not be retried — dirge PR 689 — because retrying spends the run's budget
     against a wall that will not move."))

;; Sensible fallbacks so an adapter only implements what actually differs.
(def defaults
  {:models-url (fn [_ _] nil)
   :auth-headers (fn [_ _] {})
   :parse-models (fn [_ _] [])
   :error-message (fn [_ _] nil)
   :usage-cap? (fn [_ _ _] false)})
