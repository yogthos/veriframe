(ns veriframe.llm-test
  "Phase 2: the model plumbing, offline.

  The fence parser gets the most tests here because it is the component whose
  bugs are invisible in a live run. A parser that quietly drops a tool call
  looks exactly like a model that chose not to make one."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is are]]
            [veriframe.llm.adapter :as adapter]
            [veriframe.llm.client :as client]
            [veriframe.llm.fence :as fence]
            [veriframe.llm.message :as message]
            [veriframe.llm.registry :as registry]))

;; --- fence extraction -------------------------------------------------------

(defn- fenced [body] (str "prose before\n```tool-call\n" body "\n```\nprose after"))

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

    (testing "an unknown provider names what is available"
      (is (thrown? Throwable (registry/adapter-for :nope))))))

;; --- retry policy -----------------------------------------------------------

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
