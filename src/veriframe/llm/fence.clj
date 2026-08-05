;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.llm.fence
  "Parse the model's tool call out of a ```tool-call fenced JSON block.

  The harness deliberately does not use provider-native tool calling. A fence
  works identically on every OpenAI-compatible endpoint, including ones whose
  tool-calling implementation is broken or absent, and it survives a provider
  that puts the call inside its reasoning stream.

  The cost is that malformed JSON is now the harness's problem. One repair pass
  handles the dominant failure — a multi-line SMT-LIB or Lean snippet written
  into a string value with raw newlines, which is invalid JSON per RFC 8259 and
  accounted for 5 of 35 turns in the n=500 Sidon run. Anything else is reported
  back to the model as a parse error rather than guessed at.

  Every parse records mechanics signals: whether a repair was needed, how many
  fences appeared, whether it failed outright. These feed the capability tier,
  and only the capability tier. Per the rule from dirge PR 740, a signal may
  tune a guard that fires on the same thing the signal measures, so these may
  adjust repair budgets and may never relax a verification gate."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def fence-re #"(?s)```tool-call\s*\r?\n(.*?)```")

(defn repair-control-chars
  "Escape literal control characters appearing INSIDE JSON string literals.

  A small state machine rather than a regex, because whether a newline is
  invalid depends on being inside a string, and that is not a regular
  property. Outside strings everything is copied through; inside, bare
  newline, carriage return and tab become their escape sequences.

  Does not fix unmatched backslashes or smart quotes. Those get reported."
  [^String input]
  (let [n (count input)
        sb (StringBuilder.)]
    (loop [i 0, in-string? false, escaped? false]
      (if (>= i n)
        (.toString sb)
        (let [ch (.charAt input i)]
          (cond
            escaped?
            (do (.append sb ch) (recur (inc i) in-string? false))

            (= \\ ch)
            (do (.append sb ch) (recur (inc i) in-string? true))

            (= \" ch)
            (do (.append sb ch) (recur (inc i) (not in-string?) false))

            (and in-string? (= \newline ch))
            (do (.append sb "\\n") (recur (inc i) in-string? false))

            (and in-string? (= \return ch))
            (do (.append sb "\\r") (recur (inc i) in-string? false))

            (and in-string? (= \tab ch))
            (do (.append sb "\\t") (recur (inc i) in-string? false))

            :else
            (do (.append sb ch) (recur (inc i) in-string? false))))))))

(defn- parse-error [msg extra]
  (merge {:name "__parse_error__" :args {} :parse-error msg} extra))

(defn- read-json [s]
  (try
    {:ok true :value (json/read-str s :key-fn keyword)}
    (catch Throwable e
      {:ok false :error (ex-message e)})))

(defn extract-fences
  "Every tool-call fence body in the response, in order."
  [response]
  (mapv (comp str/trim second) (re-seq fence-re (or response ""))))

(defn parse-tool-call
  "Parse a model response into a tool call.

  Returns nil when there is no fence at all, a map with `:name` and `:args` on
  success, or a `__parse_error__` map whose `:parse-error` is written for the
  model to read and correct.

  When several fences appear, the LAST one wins. A model that shows an example
  call while reasoning and then issues the real one puts the real one last, and
  a model that issues a call and then rambles rarely emits a second fence. The
  count is recorded either way rather than silently resolved, because
  `:fences > 1` is exactly the sort of tool-call mechanics the capability tier
  is built from."
  [response]
  (let [bodies (extract-fences response)]
    (when (seq bodies)
      (let [body (peek bodies)
            n (count bodies)
            repaired (repair-control-chars body)
            ;; Computed from the TEXT, not from which parse path succeeded.
            ;; clojure.data.json accepts raw newlines and tabs inside string
            ;; values where JSON.parse rejects them, so keying this off the
            ;; fallback firing would leave the counter permanently zero — a
            ;; signal that is never fed reads identically to a behaviour that
            ;; never happens (dirge PR 740). What we want to measure is that
            ;; the model emitted unescaped control characters, which is true
            ;; whichever parser tolerated it.
            needed-repair? (not= repaired body)
            base (cond-> {:fences n} needed-repair? (assoc :auto-repaired? true))
            first-try (read-json body)]
        (if (:ok first-try)
          (let [parsed (:value first-try)]
            (cond
              (not (map? parsed))
              (parse-error "tool-call body must be a JSON object, not an array or scalar" base)

              (not (string? (:name parsed)))
              (parse-error "tool-call body must have a `name` string" base)

              (str/blank? (:name parsed))
              (parse-error "tool-call `name` must not be empty" base)

              :else
              (merge base
                     {:name (:name parsed)
                      :args (let [a (:args parsed)] (if (map? a) a {}))})))

          ;; One repair pass. If the repair changed nothing there is no point
          ;; re-parsing, and the error message should name the causes the
          ;; repair does not cover.
          (if-not needed-repair?
            (parse-error
             (str (:error first-try)
                  ". Common causes: (a) a raw newline inside a string value — use \\n,"
                  " (b) an unescaped quote inside a string — use \\\","
                  " (c) an unescaped backslash — use \\\\.")
             base)
            (let [second-try (read-json repaired)]
              (if-not (:ok second-try)
                (parse-error
                 (str (:error first-try)
                      ". The harness auto-repaired literal control characters inside"
                      " string values and the result still did not parse — escape"
                      " \\n, \\r, \\t, \\\\ and \\\" inside string values.")
                 base)
                (let [parsed (:value second-try)]
                  (if (and (map? parsed)
                           (string? (:name parsed))
                           (not (str/blank? (:name parsed))))
                    (merge base
                           {:name (:name parsed)
                            :args (let [a (:args parsed)] (if (map? a) a {}))})
                    (parse-error "tool-call body must be a JSON object with a non-empty `name` string"
                                 base)))))))))))

(defn signals
  "The mechanics signals from one parse, for the capability tier.

  `:no-fence` and `:truncated` are separated on purpose. A reply that hit the
  token cap mid-thought produced no fence because it never got that far, and
  reading that as a model too weak to emit a tool call would be wrong — the
  fix is more tokens, not more steering. It was the first thing a live
  deepseek-v4-flash call did here, so it is not a hypothetical."
  [{:keys [finish-reason]} parsed]
  (let [truncated (= "length" finish-reason)]
    {:no-fence (and (nil? parsed) (not truncated))
     :truncated truncated
     :parse-error (= "__parse_error__" (:name parsed))
     :auto-repaired (boolean (:auto-repaired? parsed))
     :multiple-fences (> (or (:fences parsed) 0) 1)}))
