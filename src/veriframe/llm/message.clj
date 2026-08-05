;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.llm.message
  "Message shaping shared by every adapter.

  Two jobs, both of which have to happen the same way for every provider or
  the harness's context management differs by which model it is talking to."
  (:require [clojure.string :as str]))

(defn strip-think-blocks
  "Remove <think>…</think> from content.

  Applied to PRIOR assistant turns before they go back over the wire. Both
  DeepSeek and Zhipu document that `reasoning_content` must not be fed back on
  later turns — the model regenerates its thinking each turn — and
  accumulating it burns context for nothing. Tolerant of nesting and of an
  unmatched open tag."
  [content]
  (-> (or content "")
      (str/replace #"(?s)<think>.*?</think>" "")
      str/trim))

(defn merge-reasoning
  "Fold a provider's separate reasoning stream into the visible content with
  <think> framing.

  This is what lets one fence parser work across providers: a model that emits
  its tool call inside its reasoning is handled identically to one that emits
  it in the content, because after this they are the same string."
  [content reasoning]
  (let [c (or content "") r (or reasoning "")]
    (cond
      (and (seq r) (seq c)) (str "<think>" r "</think>\n" c)
      (seq r) (str "<think>" r "</think>")
      :else c)))

(defn prepare
  "Normalize a conversation for the wire: keyword roles become strings and
  prior assistant turns lose their think blocks. System and user messages are
  left alone."
  [messages]
  (mapv (fn [{:keys [role content] :as m}]
          (let [role (if (keyword? role) (clojure.core/name role) (str role))]
            (assoc (select-keys m [])
                   :role role
                   :content (if (and (= "assistant" role) content)
                              (strip-think-blocks content)
                              (or content "")))))
        messages))
