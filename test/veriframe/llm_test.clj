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

(deftest a-long-branch-sends-a-digest-of-its-early-turns-not-the-turns
  ;; What a branch needs from its own distant past is which approaches it
  ;; already tried and how each came out — not the prose it wrote at the time.
  ;; A 19-turn branch carries ~26KB on the wire against ~1.5KB of digest, and
  ;; the longest branch on record is 86 turns.
  ;;
  ;; The system prompt and the problem always survive, the recent turns survive
  ;; verbatim because that is the branch's working memory, and everything older
  ;; collapses to one line per turn. Details stay in the journal and the
  ;; artifacts are fetchable by id, so nothing is lost — only unloaded.
  (let [pair (fn [i] [{:role "assistant" :content (str "long reasoning " i (apply str (repeat 400 "x")))}
                      {:role "user" :content (str "result " i (apply str (repeat 400 "y")))}])
        msgs (into [{:role "system" :content "SYS"}
                    {:role "user" :content "## Problem\n\nsolve it"}]
                   (mapcat pair (range 1 21)))
        turns (mapv (fn [i] {:turn i :tool (str "tool" i)
                             :category (if (even? i) :failure :success)
                             :error (when (even? i) "boom")})
                    (range 1 21))
        out (message/compact msgs turns {:keep-pairs 4 :threshold-chars 1000})]
    (testing "the frame survives"
      (is (= "system" (:role (first out))))
      (is (= "SYS" (:content (first out))))
      (is (str/includes? (:content (second out)) "solve it")))
    (testing "the digest rides on the problem message, so roles stay alternating"
      (is (= "user" (:role (second out))))
      (is (= ["assistant" "user" "assistant" "user" "assistant" "user" "assistant" "user"]
             (mapv :role (drop 2 out)))))
    (testing "recent turns survive verbatim"
      (is (str/includes? (:content (nth out 2)) "long reasoning 17"))
      (is (str/includes? (:content (last out)) "result 20")))
    (testing "early turns are gone as prose but present as a digest"
      (let [all (str/join "\n" (map :content out))]
        (is (not (str/includes? all "long reasoning 3")) "the prose is unloaded")
        (is (str/includes? all "tool3") "but what it tried is retained")
        (is (str/includes? all "tool16"))
        (is (not (str/includes? all "tool17"))
            "turns kept verbatim are not also digested")))
    (testing "it is smaller"
      (is (< (count (str/join (map :content out)))
             (quot (count (str/join (map :content msgs))) 2))))
    (testing "a short history is left exactly alone"
      (let [short-msgs (into [{:role "system" :content "SYS"}
                              {:role "user" :content "P"}]
                             (mapcat pair (range 1 3)))]
        (is (= short-msgs (message/compact short-msgs
                                           [{:turn 1 :tool "t" :category :success}]
                                           {:keep-pairs 4 :threshold-chars 1000000})))))))

(deftest only-the-newest-settled-state-block-goes-over-the-wire
  ;; The settled-state ledger is regenerated every turn and appended to that
  ;; turn's user message, so without this every copy accumulates: gen-18's
  ;; ledger is ~6,800 tokens and an 80-turn branch would carry 80 copies of it,
  ;; which is far more context than the whole transcript.
  ;;
  ;; A ledger is STATE, not narrative — only the current one is true, and an
  ;; older copy is a strictly worse version of the newest. Same reasoning as
  ;; strip-think-blocks, which drops accumulated reasoning for the same reason.
  ;; The branch keeps every copy in its own history so the journal and resume
  ;; stay faithful; only the wire sees one.
  (let [led (fn [n] (str message/ledger-open "\nsettled: " n "\n" message/ledger-close))
        msgs [{:role "system" :content "sys"}
              {:role "user" :content (str "result 1\n" (led 1))}
              {:role "assistant" :content "call 1"}
              {:role "user" :content (str "result 2\n" (led 2))}
              {:role "assistant" :content "call 2"}
              {:role "user" :content (str "result 3\n" (led 3))}]
        out (mapv :content (message/prepare msgs))]
    (is (not (str/includes? (nth out 1) "settled: 1")) "the first ledger is dropped")
    (is (not (str/includes? (nth out 3) "settled: 2")) "and the second")
    (is (str/includes? (nth out 5) "settled: 3") "the newest survives")
    (testing "the surrounding turn result is untouched"
      (is (str/includes? (nth out 1) "result 1"))
      (is (str/includes? (nth out 3) "result 2")))
    (testing "the markers never reach the model"
      (is (not-any? #(str/includes? % message/ledger-open) out)))
    (testing "a conversation with one ledger keeps it"
      (let [one (mapv :content (message/prepare
                                [{:role "user" :content (str "r\n" (led 9))}]))]
        (is (str/includes? (first one) "settled: 9"))))))

(deftest reattach-rebuilds-the-whole-assistant-turn
  ;; Not only the parser needs this. The completion is just the TAIL of what
  ;; the assistant said, and both the message history and the journal's
  ;; assistant_text must carry the opener too — a stored turn that begins
  ;; mid-fence misrepresents the required format back to the model on every
  ;; subsequent turn, and to anyone reading the run afterwards.
  (let [prefix "```tool-call\n"]
    (is (= "```tool-call\n{\"name\": \"verify\"}"
           (fence/reattach "{\"name\": \"verify\"}" prefix)))
    (testing "a completion that already repeats the opener is left alone"
      (is (= "```tool-call\n{\"name\": \"verify\"}"
             (fence/reattach "```tool-call\n{\"name\": \"verify\"}" prefix))))
    (testing "no prefill means the completion stands as the whole turn"
      (is (= "just prose" (fence/reattach "just prose" nil)))
      (is (= "just prose" (fence/reattach "just prose" ""))))))

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
      (let [beta {:base-url "https://api.deepseek.com/beta"}
            v1   {:base-url "https://api.deepseek.com/v1"}]
        (is (adapter/prefill-support? (registry/adapter-for :deepseek) beta))
        ;; Not a property of the provider alone. On /v1 DeepSeek REJECTS the
        ;; request — "prefix is only available when using beta api" — so a
        ;; misconfigured endpoint would fail every steered turn. Checked here
        ;; so it degrades to today's behaviour instead.
        (is (not (adapter/prefill-support? (registry/adapter-for :deepseek) v1)))
        (is (not (adapter/prefill-support? (registry/adapter-for :openai) beta)))
        (is (not (adapter/prefill-support? (registry/adapter-for :ollama) beta)))))

    (testing "a supporting adapter appends the prefix as a trailing assistant turn"
      (let [a (registry/adapter-for :deepseek)
            body (adapter/chat-body a {:model "m" :base-url "https://api.deepseek.com/beta"}
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

    (testing "the models listing does not follow chat onto the beta path"
      ;; /beta is a chat-completions variant: it serves prefix completion and
      ;; returns 404 for /beta/models. Pointing the listing at it turned the
      ;; startup model check into "provider listed no models", downgrading a
      ;; real check to a warning on every start.
      (let [a (registry/adapter-for :deepseek)]
        (is (= "https://api.deepseek.com/v1/models"
               (adapter/models-url a {:base-url "https://api.deepseek.com/beta"})))
        (is (= "https://api.deepseek.com/v1/models"
               (adapter/models-url a {:base-url "https://api.deepseek.com/beta/"}))
            "a trailing slash is the same endpoint")
        (is (= "https://api.deepseek.com/v1/models"
               (adapter/models-url a {:base-url "https://api.deepseek.com/v1"}))
            "and a non-beta base is untouched")
        ;; Chat still goes where it was told.
        (is (= "https://api.deepseek.com/beta/chat/completions"
               (adapter/chat-url a {:base-url "https://api.deepseek.com/beta"})))))

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
                (prefill-support? [_ _] true)
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

(deftest reasoning-effort-is-sent-only-when-asked-for
  ;; deepseek-v4-pro thinks by default and deepseek-v4-flash does not, so
  ;; "thinking is on" was a property of which model happened to be configured
  ;; rather than something the run stated. reasoning_effort makes it explicit:
  ;; the API honours "high" and treats "none" as thinking disabled — a probe
  ;; with "none" came back with no reasoning_content and one completion token.
  ;;
  ;; Omitted from the body entirely when unset, because a provider that has
  ;; never heard of the field rejects the request rather than ignoring it.
  (let [a (registry/adapter-for :deepseek)
        req {:messages [{:role "user" :content "go"}] :max-tokens 10}]
    (testing "absent from the body when the config says nothing"
      (is (not (contains? (adapter/chat-body a {:model "m"} req) :reasoning_effort))))

    (testing "sent when the config asks for it"
      (is (= "high" (:reasoning_effort
                     (adapter/chat-body a {:model "m" :reasoning-effort "high"} req)))))

    (testing "\"none\" is a real setting and not the same as unset"
      ;; It is how thinking gets turned OFF, so dropping it as falsy-looking
      ;; would silently leave thinking on for a run that asked for neither.
      (is (= "none" (:reasoning_effort
                     (adapter/chat-body a {:model "m" :reasoning-effort "none"} req)))))))

(deftest an-xml-style-tool-call-is-accepted-rather-than-discarded
  ;; gen-30, the first run on deepseek-v4-pro. The model emits Anthropic's XML
  ;; tool syntax instead of the fenced JSON this harness documents, and the
  ;; parser saw no fence and reported a no-call. Eight of the run's first
  ;; twelve no-calls were this, on a branch that alternated failing turn and
  ;; prefill-recovered turn all the way to turn 13.
  ;;
  ;; Same reasoning as the unfenced-JSON path already here: the model said
  ;; exactly what it wanted, unambiguously, and throwing it away over
  ;; punctuation costs a turn and teaches nothing — it retries the same way,
  ;; because that IS its native format.
  ;;
  ;; Narrow, like that path. Only when no fence and no trailing JSON was
  ;; found, and only for a complete <invoke name="..."> … </invoke>.
  (testing "a plain call becomes name and args"
    (let [p (fence/parse-tool-call
             (str "Let me look.\n<tool_calls>\n<invoke name=\"lean_search\">\n"
                  "<parameter name=\"query\">List.Chain' dropLast</parameter>\n"
                  "</invoke>\n</tool_calls>"))]
      (is (= "lean_search" (:name p)))
      (is (= {:query "List.Chain' dropLast"} (:args p)))
      (is (:xml-call? p) "recorded, so it stays visible rather than silently normalised")))

  (testing "a value that is a number arrives as one"
    ;; top_k reaches (take k) and a string throws there.
    (let [p (fence/parse-tool-call
             (str "<invoke name=\"lean_search\">"
                  "<parameter name=\"query\">chain</parameter>"
                  "<parameter name=\"top_k\">8</parameter></invoke>"))]
      (is (= {:query "chain" :top_k 8} (:args p)))))

  (testing "an id that merely contains digits stays a string"
    (let [p (fence/parse-tool-call
             "<invoke name=\"fetch_artifact\"><parameter name=\"id\">s#1392</parameter></invoke>")]
      (is (= {:id "s#1392"} (:args p)))))

  (testing "a Lean body keeps its newlines, quotes and backslashes verbatim"
    ;; The whole point of the XML form is that values are not JSON-escaped.
    (let [lean "theorem t : True := by\n  simp [\"a\\b\"]\n  trivial"
          p (fence/parse-tool-call
             (str "<invoke name=\"verify_lean\">"
                  "<parameter name=\"claim\">a claim</parameter>"
                  "<parameter name=\"lean\">" lean "</parameter></invoke>"))]
      (is (= lean (get-in p [:args :lean])))))

  (testing "a real fence still wins"
    ;; A model that shows the XML while reasoning and then issues a proper
    ;; fenced call must not have the reasoning parsed as its call.
    (let [p (fence/parse-tool-call
             (str "<invoke name=\"lean_search\"><parameter name=\"query\">x</parameter></invoke>\n"
                  "```tool-call\n{\"name\": \"verify_lean\", \"args\": {\"claim\": \"c\"}}\n```"))]
      (is (= "verify_lean" (:name p)))
      (is (not (:xml-call? p)))))

  (testing "the last invoke wins, as the last fence does"
    (let [p (fence/parse-tool-call
             (str "<invoke name=\"lean_search\"><parameter name=\"query\">first</parameter></invoke>\n"
                  "<invoke name=\"fetch_artifact\"><parameter name=\"id\">827</parameter></invoke>"))]
      (is (= "fetch_artifact" (:name p)))))

  (testing "prose about the format is not a call"
    (is (nil? (fence/parse-tool-call
               "Do not use <invoke name=...> syntax; use the fenced form.")))
    (is (nil? (fence/parse-tool-call "<invoke name=\"lean_search\">unterminated"))
        "an opener with no closer is not a call")
    (is (nil? (fence/parse-tool-call "<invoke>no name here</invoke>")))))

(deftest the-reasoning-stream-survives-onto-the-response
  ;; turns.reasoning_text was empty for every run ever recorded. Not because
  ;; nothing reasoned — agent/loop writes :reasoning-text (:reasoning response)
  ;; on every turn — but because the client folds the provider's reasoning into
  ;; <think> framing on :content and then drops the key, so that write always
  ;; stored nil. Querying the column to ask whether a model reasoned returned
  ;; absence, which reads as "it did not".
  ;;
  ;; The fold stays: it is what lets one fence parser work across providers.
  ;; The key is carried alongside it, which is additive.
  (let [adapter (reify adapter/Adapter
                  (id [_] :fake)
                  (display-name [_] "Fake")
                  (chat-url [_ _] "http://example.invalid/chat")
                  (auth-headers [_ _] {})
                  (chat-body [_ _ _] {})
                  (prefill-support? [_ _] false)
                  (error-message [_ _] nil)
                  (usage-cap? [_ _ _] false)
                  (parse-chat [_ _] {:content "1183"
                                     :reasoning "91*10=910, 91*3=273"
                                     :finish-reason "stop"}))]
    (with-redefs [http/post (fn [& _] {:status 200 :body "{}"})]
      (let [r (client/chat adapter {:model "m"} [{:role "user" :content "go"}])]
        (is (= "<think>91*10=910, 91*3=273</think>\n1183" (:content r))
            "the fold is unchanged — one parser still sees one string")
        (is (= "91*10=910, 91*3=273" (:reasoning r))
            "and the reasoning is still reachable on its own, for the column that stores it")))))
