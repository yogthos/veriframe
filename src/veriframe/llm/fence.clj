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

;; Any opener paired with any closer.
;;
;; The model knows two spellings of the wrapper — the documented ```tool-call
;; fence and <tool-call> tags — and mixes them: it opens with one and closes
;; with the other. Requiring a matching pair cost gen-30 thirteen turns in a
;; sixty-turn window, one of them 30,658 characters carrying a complete
;; recursive extraction proof, thrown away for the wrong bracket at the end.
;;
;; Both spellings are unmistakable in intent, so the body between them is
;; treated exactly like the documented fence's, malformed ones included: those
;; earn a parse error, which is what lets a branch correct itself.
;;
;; The CLOSER also takes the plural and the underscore — </tool-calls>,
;; </tool_call>, </tool_calls>. gen-31 B1.2 opened with the documented fence
;; and closed with </tool-calls> on three consecutive turns; `</tool-call>` did
;; not match, the `s` sitting where the `>` was expected, and what was thrown
;; away was a proof of edge-choice injectivity, one of the three gaps that run
;; existed to cross. Same shape as the gen-30 loss, one letter over.
;;
;; The OPENER is deliberately NOT widened to <tool_calls>. That is the wrapper
;; of the XML tool syntax, and xml-call is the last rung — reached only when no
;; fence matched — so treating it as a fence opener would capture a good XML
;; call into the fence path and turn it into a parse error.
(def fence-re
  #"(?s)(?:```tool-call\s*\r?\n|<tool-call>\s*)(.*?)(?:```|</tool[-_]calls?>)")

(def ^:private json-fence-re #"(?s)```json\s*\r?\n(.*?)```")

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
  "Every tool-call fence body in the response, in order.

  Either spelling of the deliberate wrapper, and any pairing of them — see
  fence-re. A model that writes one is unambiguously calling a tool, and only
  the punctuation differs."
  [response]
  (mapv (comp str/trim second) (re-seq fence-re (or response ""))))

(defn- json-fence
  "A ```json fence whose body is the documented call shape, or nil.

  Guarded where the two wrappers above are not, because ```json is what a
  model reaches for to show data as well. gen-30 emitted
  {\"lean_search\": {…}} — the tool name as the KEY — and accepting that would
  mean this parser deciding which strings are tool names, which is the
  registry's business and not punctuation's. An unrecognised shape stays a
  no-call rather than becoming a parse error, on the same reasoning as
  trailing-call: telling a model its call is broken when it never made one
  sends it looking in the wrong place."
  [response]
  (when-let [body (some-> (last (re-seq json-fence-re (or response "")))
                          second str/trim)]
    (let [{:keys [ok value]} (read-json (repair-control-chars body))]
      (when (and ok (map? value) (string? (:name value)) (not (str/blank? (:name value))))
        body))))

;; A trailing JSON object, for a response that ends in a well-formed call and
;; simply omits the fence. Anchored to the END of the response and required to
;; balance from a `{` that starts a line, so a JSON example quoted mid-argument
;; cannot be mistaken for the call.
;; `[\s)\]]*` rather than `\s*` after the object: a model that over-closes its
;; own call must not lose it. gen-35 B2.2 ended a complete `thesis` payload —
;; all four arguments, valid JSON — with `"}})`, one paren too many, then
;; repeated that identical 663-token response four times and was culled for
;; "could not emit a well-formed fence". It had opened ```tool-call and never
;; closed it, which this rung exists to forgive; the single stray `)` is what
;; defeated it. Only stray CLOSERS and whitespace are tolerated, so trailing
;; prose still means no call.
(def ^:private trailing-object-re #"(?s)(?:^|\n)\s*(\{.*\})[\s)\]]*\z")

(defn- trailing-call
  "The response's final top-level JSON object, but only if it is plausibly a
  tool call: it must parse and carry a non-blank `name`.

  Deliberately silent otherwise. A response ending in some other JSON should
  still be reported as having no tool call, rather than as a malformed one,
  because telling a model its call is broken when it never made one sends it
  looking in the wrong place."
  [response]
  (when-let [candidate (some-> (re-find trailing-object-re (or response ""))
                               second str/trim)]
    (let [{:keys [ok value]} (read-json (repair-control-chars candidate))]
      (when (and ok (map? value) (string? (:name value)) (not (str/blank? (:name value))))
        candidate))))

;; Anthropic's XML tool syntax, which deepseek-v4-pro emits in place of the
;; fenced JSON this harness documents. Both parts are required — an opener with
;; no closer is not a call — so prose ABOUT the format does not become one.
;; `[^>]*` rather than `\s*` before the tag closes: the attribute list does
;; not stop at `name`. deepseek-v4-pro writes
;; `<parameter name="query" string="true">`, and requiring the tag to close
;; immediately after the name made every such parameter fail to match while
;; `<invoke>`, which carries no extra attribute, matched fine — so the call
;; arrived with the right name and NO arguments, and the branch was told
;; "Missing required argument(s): query" with the query sitting in the tag.
;; gen-31 B3 was culled for a malformed fence it had not emitted (2026-08-18).
(def ^:private invoke-re
  #"(?s)<invoke\s+name=\"([^\"]+)\"[^>]*>(.*?)</invoke>")

(def ^:private parameter-re
  #"(?s)<parameter\s+name=\"([^\"]+)\"[^>]*>(.*?)</parameter>")

(defn- xml-value
  "A parameter's value. Verbatim, except that something which is entirely a
  number becomes one.

  Values here are NOT JSON-escaped — that is the whole reason a model reaches
  for this form when handing over a Lean proof — so the text is kept exactly
  as written, newlines, quotes and backslashes included. The number case is
  not cosmetic: `top_k` reaches `(take k)` and a string throws there. Anchored
  and strict, so `s#1392` and a claim that merely mentions a figure stay
  strings."
  [s]
  (let [t (str/trim s)]
    (cond
      (re-matches #"-?\d+" t) (parse-long t)

      ;; A JSON array or object. The XML parameter form has no way to express
      ;; one, so a model handing over `subClaims` writes the JSON text as the
      ;; body. Returned verbatim that is a String where the caller wants a
      ;; collection, which seqs into Characters and dies far from here — in the
      ;; journal writer, with "Don't know how to write JSON of class
      ;; java.lang.Character", taking the branch with it. gen-31 lost B1.3 and
      ;; B2.2 that way, both of them on the run's actual target.
      ;;
      ;; Only when it really parses. Lean bodies and prose contain brackets,
      ;; and a value that merely starts with one stays the string it is.
      (and (or (str/starts-with? t "[") (str/starts-with? t "{"))
           (str/includes? t "\""))
      (let [{:keys [ok value]} (read-json t)]
        (if (and ok (coll? value)) value s))

      :else s)))

(defn- xml-call
  "The response's last complete <invoke>, as {:name :args}, or nil.

  Last rather than first, matching the fence rule and for the same reason: a
  model that drafts one call while reasoning and then issues the real one puts
  the real one last."
  [response]
  (when-let [m (last (re-seq invoke-re (or response "")))]
    (let [[_ nm body] m]
      (when-not (str/blank? nm)
        {:name nm
         :args (reduce (fn [acc [_ k v]] (assoc acc (keyword k) (xml-value v)))
                       {} (re-seq parameter-re (or body "")))}))))

(defn reattach
  "The complete assistant turn, given what the request was prefilled with.

  A prefilled request ends mid-fence and the model continues from there
  WITHOUT repeating the opener, so the raw completion is only the tail of what
  the assistant actually said. Both the parser and the transcript need the
  whole thing: the parser because it matches on the opener, and the message
  history because an assistant turn that begins mid-fence misrepresents the
  format back to the model on every later turn.

  Providers differ on whether the prefix comes back in the completion, so a
  response that already starts with it is left alone — reattaching blindly
  would produce two openers whose first fence body is empty."
  [response prefill]
  (if (and (seq prefill)
           (not (str/starts-with? (str/triml (str response)) (str/triml prefill))))
    (str prefill response)
    (str response)))

(declare parse-tool-call*)

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
  is built from.

  `opts` may carry `:prefill`, the partial assistant text the request ended
  with. The model continues from it and does not repeat it, so the response
  begins INSIDE the fence and the opener this function matches on is missing
  from the text — every prefilled turn would read as a no-call, which is the
  opposite of what prefilling is for. The prefix is reattached first, unless
  the response already repeats it: providers differ on that, and reattaching
  blindly would produce two openers whose first fence body is empty."
  ([response] (parse-tool-call response nil))
  ([response {:keys [prefill]}]
   (parse-tool-call* (if (seq prefill) (reattach response prefill) response))))

(defn- parse-tool-call* [response]
  (let [fenced (extract-fences response)
        ;; A response that ends in a well-formed call but omits the fence is
        ;; accepted, and recorded as :unfenced? so it stays visible rather than
        ;; being quietly normalised. Measured at 23 of 34 turns in one run: the
        ;; model emitted exactly the right JSON and the harness discarded it
        ;; over formatting, then told it to try again, which it did the same
        ;; way. That is a whole run lost to punctuation.
        ;;
        ;; Narrow on purpose. It applies only when NO fence was found, only to
        ;; the very end of the response, and only if the object carries a
        ;; `name` — the same validation a fenced body gets. A drafted example
        ;; followed by a real fenced call is unaffected, because the fence wins.
        bodies (if (seq fenced)
                 fenced
                 (when-let [t (or (trailing-call response) (json-fence response))]
                   [t]))
        unfenced? (and (empty? fenced) (seq bodies))]
    ;; Third rung, and last: only when neither a fence nor a trailing JSON
    ;; object was found. Recorded rather than silently normalised, for the same
    ;; reason :unfenced? is — a run where the model never once used the
    ;; documented format is a fact about the arm, not a detail.
    (if (empty? bodies)
      (when-let [x (xml-call response)]
        (assoc x :fences 0 :xml-call? true))
      (when (seq bodies)
      (let [body (peek bodies)
            n (count fenced)
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
            base (cond-> {:fences n}
                   needed-repair? (assoc :auto-repaired? true)
                   unfenced? (assoc :unfenced? true))
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
                                 base))))))))))))

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
