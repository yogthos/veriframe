;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.llm-test
  "Phase 2: the model plumbing, offline.

  The fence parser gets the most tests here because it is the component whose
  bugs are invisible in a live run. A parser that quietly drops a tool call
  looks exactly like a model that chose not to make one."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is are]]
            [jolt.http-client :as http]
            [veriframe.llm.adapter :as adapter]
            [veriframe.llm.client :as client]
            [veriframe.llm.fence :as fence]
            [veriframe.llm.message :as message]
            [veriframe.llm.registry :as registry]))

;; --- fence extraction -------------------------------------------------------

(defn- fenced [body] (str "prose before\n```tool-call\n" body "\n```\nprose after"))

(deftest a-prefilled-response-parses-as-if-it-carried-its-own-fence
  ;; The trap in prefilling the opening fence: the model does not repeat it,
  ;; so the response body starts INSIDE the fence and fence-re — which matches
  ;; on the ```tool-call opener — finds nothing. Every prefilled turn would
  ;; parse as a no-call, turning a fix for __no_call__ into a generator of
  ;; them. The prefix has to be reattached before matching.
  (let [prefix "```tool-call\n"]
    (testing "the model continues inside the fence and closes it"
      (let [p (fence/parse-tool-call "{\"name\": \"verify\", \"args\": {\"claim\": \"x\"}}\n```"
                                     {:prefill prefix})]
        (is (= "verify" (:name p)))
        (is (= {:claim "x"} (:args p)))))

    (testing "a model that repeats the opener anyway does not get a doubled fence"
      ;; Providers differ on whether the prefix comes back in the completion.
      ;; Reattaching blindly would produce ```tool-call\n```tool-call\n{...},
      ;; whose first fence body is empty — a parse error on a turn that was
      ;; actually fine.
      (let [p (fence/parse-tool-call (str prefix "{\"name\": \"verify\"}\n```")
                                     {:prefill prefix})]
        (is (= "verify" (:name p)))
        (is (= 1 (:fences p)) "one fence, not two")))

    (testing "a prefilled response that never closes the fence still parses"
      ;; Hitting the token cap mid-call is common; the closing fence is the
      ;; first casualty. The unfenced-tail path already handles this shape
      ;; without a prefill and must keep doing so with one.
      (let [p (fence/parse-tool-call "{\"name\": \"proof_state\"}" {:prefill prefix})]
        (is (= "proof_state" (:name p)))))

    (testing "without a prefill nothing changes"
      ;; The whole non-prefilled surface must be untouched, including the
      ;; mechanics signals the capability tier is built from.
      (is (nil? (fence/parse-tool-call "just prose")))
      (is (nil? (fence/parse-tool-call "just prose" {})))
      (let [a (fence/parse-tool-call (fenced "{\"name\": \"verify\"}"))
            b (fence/parse-tool-call (fenced "{\"name\": \"verify\"}") {})]
        (is (= a b))))))

(deftest fence-basics
  (testing "no fence at all is nil, not an error"
    (is (nil? (fence/parse-tool-call "I think the answer is 42.")))
    (is (nil? (fence/parse-tool-call ""))))

  (testing "a well-formed call"
    (let [p (fence/parse-tool-call (fenced "{\"name\": \"verify\", \"args\": {\"claim\": \"x\"}}"))]
      (is (= "verify" (:name p)))
      (is (= {:claim "x"} (:args p)))
      (is (= 1 (:fences p)))
      (is (not (:auto-repaired? p)))))

  (testing "args is optional and defaults to an empty map"
    (is (= {} (:args (fence/parse-tool-call (fenced "{\"name\": \"proof_state\"}"))))))

  (testing "a non-map args is ignored rather than propagated as garbage"
    (is (= {} (:args (fence/parse-tool-call (fenced "{\"name\": \"x\", \"args\": [1,2]}")))))))

(deftest fence-rejects-bad-shapes
  (are [body] (= "__parse_error__"
                 (:name (fence/parse-tool-call (fenced body))))
    "[1, 2, 3]"
    "\"just a string\""
    "{\"args\": {}}"
    "{\"name\": \"\"}"
    "{\"name\": 42}"
    "{not json at all")

  (testing "the error text names the causes the repair pass does not cover"
    (let [p (fence/parse-tool-call (fenced "{\"name\": \"x\", \"args\": {\"a\": \"un\"escaped\"}}"))]
      (is (= "__parse_error__" (:name p)))
      (is (str/includes? (:parse-error p) "unescaped quote")))))

(deftest fence-control-char-repair
  (testing "a raw newline inside a string value is repaired"
    ;; The dominant real failure: the model writes multi-line SMT-LIB or Lean
    ;; straight into a string value. 5 of 35 turns in the n=500 Sidon run.
    (let [body "{\"name\": \"verify_smt\", \"args\": {\"smtlib\": \"(declare-const x Int)\n(assert (> x 2))\"}}"
          p (fence/parse-tool-call (fenced body))]
      (is (= "verify_smt" (:name p)))
      (is (:auto-repaired? p))
      (is (= "(declare-const x Int)\n(assert (> x 2))"
             (get-in p [:args :smtlib]))
          "the repaired value must round-trip to the original text")))

  (testing "tabs and carriage returns too"
    (let [p (fence/parse-tool-call (fenced "{\"name\": \"x\", \"args\": {\"c\": \"a\tb\r\nc\"}}"))]
      (is (= "a\tb\r\nc" (get-in p [:args :c])))
      (is (:auto-repaired? p))))

  (testing "control chars OUTSIDE strings are left alone, since they are legal"
    (let [p (fence/parse-tool-call (fenced "{\n  \"name\": \"x\",\n  \"args\": {}\n}"))]
      (is (= "x" (:name p)))
      (is (not (:auto-repaired? p)) "pretty-printed JSON is not a repair case")))

  (testing "an escaped backslash before a quote does not confuse the state machine"
    ;; "a\\" ends the string; a naive scanner reads \\" as an escaped quote and
    ;; thinks it is still inside one, then escapes every later newline.
    (let [body "{\"name\": \"x\", \"args\": {\"p\": \"a\\\\\"}}"]
      (is (= "a\\" (get-in (fence/parse-tool-call (fenced body)) [:args :p])))))

  (testing "repair on a body that is broken beyond control characters still fails"
    (let [p (fence/parse-tool-call (fenced "{\"name\": \"x\", \"args\": {\"c\": \"a\nb\""))]
      (is (= "__parse_error__" (:name p)))
      (is (:auto-repaired? p) "the repair was attempted and is recorded even though it failed"))))

(deftest fence-multiple
  (testing "the LAST fence wins, and the count is recorded rather than hidden"
    (let [resp (str "```tool-call\n{\"name\": \"example\", \"args\": {}}\n```\n"
                    "now the real one\n"
                    "```tool-call\n{\"name\": \"real\", \"args\": {\"k\": 1}}\n```")
          p (fence/parse-tool-call resp)]
      (is (= "real" (:name p)))
      (is (= 2 (:fences p)))))

  (testing "the model drafting inside <think> before the real call"
    ;; Not an edge case. Measured at 20.5% of 200 live deepseek-v4-flash
    ;; responses, and in all 41 the first fence was inside <think> and the last
    ;; carried at least as many args. Taking the first — which is what
    ;; String.match without /g does in the TypeScript original — loses one turn
    ;; in five.
    ;;
    ;; This body is trimmed from a real captured response. The first fence is
    ;; the model quoting the system prompt's own template back at itself, which
    ;; is not even valid JSON, so first-fence would spend the turn on a parse
    ;; error while a perfectly good call sat further down the same response.
    (let [resp (str "<think>the format says:\n"
                    "```tool-call\n{\"name\": \"...\", \"args\": {...}}\n```\n"
                    "so I should emit</think>\n"
                    "```tool-call\n"
                    "{\"name\": \"add_rule\", \"args\": {\"name\": \"transitive_closure\","
                    " \"code\": \"tc(X, Y) :- edge(X, Y).\\ntc(X, Y) :- edge(X, Z), tc(Z, Y).\"}}"
                    "\n```")
          p (fence/parse-tool-call resp)]
      (is (= "add_rule" (:name p)))
      (is (= 2 (:fences p)))
      (is (str/includes? (get-in p [:args :code]) "tc(X, Y) :- edge(X, Y)."))
      (is (true? (:multiple-fences (fence/signals {:finish-reason "stop"} p)))))))

(deftest fence-signals
  (testing "a truncated reply is not the same as a model that skipped the call"
    ;; deepseek-v4-flash spent its whole budget inside <think> on the first
    ;; live call here. Treating that as a steering problem would be wrong: the
    ;; fix is more tokens.
    (is (= {:no-fence false :truncated true :parse-error false
            :auto-repaired false :multiple-fences false}
           (fence/signals {:finish-reason "length"} nil)))
    (is (= {:no-fence true :truncated false :parse-error false
            :auto-repaired false :multiple-fences false}
           (fence/signals {:finish-reason "stop"} nil)))))

;; --- messages ---------------------------------------------------------------

(deftest think-blocks
  (testing "think blocks are stripped from prior assistant turns"
    (is (= "the answer" (message/strip-think-blocks "<think>musing</think>\nthe answer")))
    (is (= "" (message/strip-think-blocks "<think>only musing</think>"))))

  (testing "multiple blocks and content across newlines"
    (is (= "a\nb" (message/strip-think-blocks "<think>x</think>a\nb<think>y\nz</think>"))))

  (testing "only assistant turns are touched"
    (let [out (message/prepare [{:role :system :content "<think>keep</think>sys"}
                                {:role :user :content "<think>keep</think>usr"}
                                {:role :assistant :content "<think>drop</think>asst"}])]
      (is (= ["<think>keep</think>sys" "<think>keep</think>usr" "asst"]
             (mapv :content out)))
      (is (= ["system" "user" "assistant"] (mapv :role out)))))

  (testing "reasoning is folded into <think> framing so one parser handles both"
    (is (= "<think>r</think>\nc" (message/merge-reasoning "c" "r")))
    (is (= "<think>r</think>" (message/merge-reasoning nil "r")))
    (is (= "c" (message/merge-reasoning "c" nil)))))

;; --- adapters ---------------------------------------------------------------

(deftest adapters-differ-only-where-they-should
  (let [cfg {:base-url "https://api.example.com/v1" :model "m" :api-key "k"}]
    (testing "the OpenAI family"
      (let [a (registry/adapter-for :deepseek)]
        (is (= "https://api.example.com/v1/chat/completions" (adapter/chat-url a cfg)))
        (is (= {"Authorization" "Bearer k"} (adapter/auth-headers a cfg)))
        (is (= {:model "m" :messages [] :max_tokens 10 :temperature 0.5}
               (adapter/chat-body a cfg {:messages [] :max-tokens 10 :temperature 0.5})))))

    (testing "an endpoint with no key sends no auth header"
      (is (= {} (adapter/auth-headers (registry/adapter-for :local) (dissoc cfg :api-key)))))

    (testing "Ollama's native shape: one object, options nested, stream off"
      (let [a (registry/adapter-for :ollama)
            body (adapter/chat-body a {:model "q"} {:messages [] :max-tokens 10 :temperature 0.5})]
        (is (false? (:stream body)) "without this the reply is newline-delimited JSON")
        (is (= {:num_predict 10 :temperature 0.5} (:options body)))))

    (testing "reasoning lives under a different key per provider"
      (let [reply {:choices [{:message {:content "c" :reasoning_content "r"}
                              :finish_reason "stop"}]}]
        (is (= "r" (:reasoning (adapter/parse-chat (registry/adapter-for :deepseek) reply))))
        (is (nil? (:reasoning (adapter/parse-chat (registry/adapter-for :openai) reply))))))

    (testing "Ollama reads content and usage from its own field names"
      (let [reply {:message {:content "c" :thinking "t"} :done_reason "stop"
                   :prompt_eval_count 3 :eval_count 7}
            p (adapter/parse-chat (registry/adapter-for :ollama) reply)]
        (is (= "c" (:content p)))
        (is (= "t" (:reasoning p)))
        (is (= {:prompt-tokens 3 :completion-tokens 7 :total-tokens 10} (:usage p)))))

    (testing "cache token counts are kept when the provider reports them"
      ;; Providers that do prefix caching return the split alongside the
      ;; totals. veriframe threw it away, so nothing could answer whether a
      ;; wide beam thrashes the cache — every branch carries its own growing
      ;; message list, so the beam holds N diverging prefixes at once and
      ;; whether that is cheap was unknowable.
      (let [reply {:choices [{:message {:content "c"} :finish_reason "stop"}]
                   :usage {:prompt_tokens 100 :completion_tokens 20 :total_tokens 120
                           :prompt_cache_hit_tokens 80 :prompt_cache_miss_tokens 20}}
            u (:usage (adapter/parse-chat (registry/adapter-for :deepseek) reply))]
        (is (= 100 (:prompt-tokens u)))
        (is (= 80 (:cache-hit-tokens u)))
        (is (= 20 (:cache-miss-tokens u)))))

    (testing "a provider that reports no cache split omits the keys rather than zeroing them"
      ;; Zero and absent are different claims. A zero would say the cache was
      ;; missed on every token; absent says the provider did not tell us. The
      ;; whole point of capturing this is to reason about cache behaviour, and
      ;; a fabricated zero would poison exactly that.
      (let [reply {:choices [{:message {:content "c"} :finish_reason "stop"}]
                   :usage {:prompt_tokens 100 :completion_tokens 20 :total_tokens 120}}
            u (:usage (adapter/parse-chat (registry/adapter-for :deepseek) reply))]
        (is (= 100 (:prompt-tokens u)))
        (is (not (contains? u :cache-hit-tokens)))
        (is (not (contains? u :cache-miss-tokens)))))

    (testing "prefill is offered only by providers that actually support it"
      ;; A fenced tool-call protocol lets the model answer in prose instead,
      ;; which is veriframe's dominant mechanical failure. Sending the opening
      ;; fence as a partial assistant message removes that option — but only
      ;; some providers continue a trailing assistant turn. OpenAI does not,
      ;; and asking it to would either be ignored or rejected, so the
      ;; capability is declared rather than assumed.
      (is (adapter/prefill-support? (registry/adapter-for :deepseek)))
      (is (not (adapter/prefill-support? (registry/adapter-for :openai))))
      (is (not (adapter/prefill-support? (registry/adapter-for :ollama)))))

    (testing "a supporting adapter appends the prefix as a trailing assistant turn"
      (let [a (registry/adapter-for :deepseek)
            body (adapter/chat-body a {:model "m"}
                                    {:messages [{:role "user" :content "go"}]
                                     :prefill "```tool-call\n"})
            msgs (:messages body)]
        (is (= 2 (count msgs)))
        (is (= "assistant" (:role (last msgs))))
        (is (= "```tool-call\n" (:content (last msgs))))
        ;; DeepSeek continues a trailing assistant message only when it is
        ;; flagged; without this the message is treated as a completed turn
        ;; and the model replies after it rather than inside it.
        (is (true? (:prefix (last msgs))))))

    (testing "the client threads prefill through to the adapter"
      ;; The plumbing gap that would make all of the above dead code: chat
      ;; builds the request map from its opts, so a key it does not name never
      ;; reaches chat-body at all, and the prefill would silently never happen.
      (let [seen (atom nil)
            a (reify adapter/Adapter
                (id [_] :probe)
                (display-name [_] "probe")
                (chat-url [_ _] "http://localhost/x")
                (models-url [_ _] nil)
                (auth-headers [_ _] {})
                (chat-body [_ _ req] (reset! seen req) {})
                (parse-chat [_ _] nil)
                (parse-models [_ _] [])
                (error-message [_ _] nil)
                (prefill-support? [_] true)
                (usage-cap? [_ _ _] false))]
        (try (client/chat a {:max-retries 0} [{:role "user" :content "hi"}]
                          {:prefill "```tool-call\n"})
             (catch Throwable _ nil))
        (is (= "```tool-call\n" (:prefill @seen))
            "chat must name :prefill in its opts destructuring or it is dropped")))

    (testing "a non-supporting adapter ignores prefill entirely"
      ;; Must be byte-identical to the no-prefill body: a provider that does
      ;; not support this has to be left on exactly the path it is on today.
      (let [a (registry/adapter-for :openai)
            req {:messages [{:role "user" :content "go"}] :max-tokens 5}]
        (is (= (adapter/chat-body a {:model "m"} req)
               (adapter/chat-body a {:model "m"} (assoc req :prefill "```tool-call\n"))))))

    (testing "an unknown provider names what is available"
      (is (thrown? Throwable (registry/adapter-for :nope))))))

;; --- retry policy -----------------------------------------------------------

(deftest every-provider-call-bounds-its-connect
  ;; http-client honours :conn-timeout as of v0.0.3 (a variadic-fcntl fix);
  ;; before that it was ignored and a connect was bounded only by the
  ;; kernel's SYN retry limit, about 75s on macOS. Now that the option has
  ;; teeth, a call that omits it is the one that hangs — and list-models,
  ;; the boot-time reachability probe, was exactly that call.
  (let [cfg {:base-url "https://api.example.com/v1" :model "m" :api-key "k"
             :conn-timeout-ms 4321}
        a (registry/adapter-for :deepseek)
        seen (atom nil)]
    (testing "the chat call"
      (with-redefs [http/post
                    (fn [_ opts]
                      (reset! seen opts)
                      {:status 200
                       :body (json/write-str
                              {:choices [{:message {:content "ok"}
                                          :finish_reason "stop"}]})})]
        (client/chat a cfg [{:role "user" :content "hi"}])
        (is (= 4321 (:conn-timeout @seen)))))
    (testing "the models probe"
      (with-redefs [http/get (fn [_ opts] (reset! seen opts)
                               {:status 200 :body "{\"data\":[]}"})]
        (client/list-models a cfg)
        (is (= 4321 (:conn-timeout @seen)))))
    (testing "a config with no explicit value still bounds it"
      (with-redefs [http/get (fn [_ opts] (reset! seen opts)
                               {:status 200 :body "{\"data\":[]}"})]
        (client/list-models a (dissoc cfg :conn-timeout-ms))
        (is (pos? (:conn-timeout @seen)))))))

(deftest error-classification
  (let [a (registry/adapter-for :deepseek)]
    (testing "transient statuses retry"
      (are [status] (= :retry (client/classify a status nil))
        429 500 502 503 408))

    (testing "client errors do not"
      (are [status] (= :fatal (client/classify a status nil))
        400 401 403 404 422))

    (testing "a 429 that means out of credit is a wall, not a window"
      ;; dirge PR 689: retrying a usage cap spends the run's budget against
      ;; something that will not move before the run ends.
      (is (= :fatal (client/classify a 429 {:error {:message "Insufficient Balance"}})))
      (is (= :fatal (client/classify a 429 {:error {:message "You exceeded your current quota"}})))
      (is (= :retry (client/classify a 429 {:error {:message "Rate limit reached, slow down"}}))))))

(deftest retry-after-headers
  ;; dirge PR 719: waiting exactly as long as the provider asked beats
  ;; doubling a guess.
  (testing "retry-after in seconds"
    (is (= 5000 (client/retry-after-ms {"retry-after" "5"})))
    (is (= 5000 (client/retry-after-ms {"Retry-After" " 5 "}))))

  (testing "the x-ratelimit-reset family, with or without a unit suffix"
    (is (= 3000 (client/retry-after-ms {"x-ratelimit-reset-requests" "3s"})))
    (is (= 2000 (client/retry-after-ms {"x-ratelimit-reset-tokens" "2"}))))

  (testing "the provider's opinion is bounded by ours"
    (is (= client/max-backoff-ms (client/retry-after-ms {"retry-after" "3600"}))))

  (testing "no header means fall back to the ladder"
    (is (nil? (client/retry-after-ms {})))
    (is (nil? (client/retry-after-ms {"retry-after" "not-a-number"})))))

;; --- a call with no fence ---------------------------------------------------

(deftest an-unfenced-trailing-call-is-accepted
  ;; Measured at 23 of 34 turns in one run: the model emitted exactly the right
  ;; JSON, omitted the fence, and the harness threw it away and asked it to try
  ;; again, which it did the same way. A whole run lost to punctuation.
  (let [r (fence/parse-tool-call
           "I'll check positive definiteness.\n\n{\"name\": \"verify_octave\", \"args\": {\"claim\": \"A is PD\", \"expr\": \"all(eig(A) > 0)\"}}")]
    (is (= "verify_octave" (:name r)))
    (is (= "A is PD" (get-in r [:args :claim])))
    (is (:unfenced? r) "the signal is recorded rather than silently normalised")
    (is (= 0 (:fences r)))))

(deftest a-fence-still-wins-over-trailing-json
  ;; The fallback must not change how a well-formed response is read. A model
  ;; that fences its call and then prints data after it gets the fenced call.
  (let [r (fence/parse-tool-call
           "```tool-call\n{\"name\": \"verify\", \"args\": {\"claim\": \"real\"}}\n```\n\n{\"name\": \"decoy\", \"args\": {}}")]
    (is (= "verify" (:name r)))
    (is (not (:unfenced? r)))))

(deftest trailing-json-that-is-not-a-call-is-not-a-call
  ;; Reporting this as a malformed call would send the model looking for a
  ;; mistake it did not make. It has no tool call, which is a different thing.
  (is (nil? (fence/parse-tool-call "Here is the matrix:\n\n{\"rows\": 3, \"cols\": 3}")))
  (is (nil? (fence/parse-tool-call "no json at all here")))
  (is (nil? (fence/parse-tool-call "{\"name\": \"\", \"args\": {}}"))))

(deftest an-unfenced-call-still-gets-control-char-repair
  (let [r (fence/parse-tool-call
           "{\"name\": \"verify_lean\", \"args\": {\"lean\": \"theorem t : True := by\ntrivial\"}}")]
    (is (= "verify_lean" (:name r)))
    (is (:unfenced? r))
    (is (:auto-repaired? r))))
