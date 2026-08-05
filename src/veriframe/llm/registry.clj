;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.llm.registry
  "Provider keyword to adapter.

  The only place that knows the full set. Adding a provider means writing an
  adapter and adding one line here; nothing in the loop or the client changes."
  (:require [clojure.string :as str]
            [veriframe.llm.adapter :as adapter]
            [veriframe.llm.adapter.ollama :as ollama]
            [veriframe.llm.adapter.openai :as openai]))

(def adapters
  {:deepseek openai/deepseek
   :glm openai/glm
   :openai openai/openai
   :local openai/local
   :ollama ollama/ollama})

(defn adapter-for
  "The adapter for a provider keyword. Throws naming what is available rather
  than returning nil, since a nil adapter fails much later and less clearly."
  [provider]
  (or (get adapters provider)
      (throw (ex-info (str "No adapter for provider " provider
                           ". Known: " (str/join ", " (sort (map name (keys adapters)))))
                      {:provider provider :known (keys adapters)}))))

(defn describe
  "One line per adapter, for /v1/models and for logging."
  []
  (for [[k a] (sort-by key adapters)]
    {:provider k :name (adapter/display-name a)}))
