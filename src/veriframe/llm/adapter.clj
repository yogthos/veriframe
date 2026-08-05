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
     {:messages [{:role :content}] :max-tokens n :temperature d}.
     Returned as data; the client encodes it.")

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
