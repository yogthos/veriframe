(ns veriframe.llm.client
  "The provider-independent half of talking to a model.

  Everything here applies identically whichever adapter is in play, which is
  the point: a retry ladder that differs by provider is a retry ladder nobody
  can reason about.

  Three behaviours are worth naming.

  A 429 is not one thing. `Retry-After` and the `x-ratelimit-reset-*` headers
  say when the window reopens, and waiting exactly that long beats doubling a
  guess — dirge PR 719. A 429 that means the account is out of credit is a
  wall, not a window, and retrying it burns the run's budget against something
  that will not move, so the adapter gets to say which it is — dirge PR 689.

  A reply with neither content nor reasoning is an error, not an empty answer.
  It usually means the model spent its whole budget thinking, and reporting it
  as a successful empty turn would send the loop round again with nothing.

  Prior assistant turns lose their think blocks on the way out. See
  veriframe.llm.message."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jolt.http-client :as http]
            [veriframe.llm.adapter :as adapter]
            [veriframe.llm.message :as message]))

(def default-max-retries 2)
(def default-timeout-ms 300000)
;; Never sleep longer than this on a provider's say-so. A header asking for
;; ten minutes should not silently become a ten-minute stall.
(def max-backoff-ms 60000)

;; --- error classification ---------------------------------------------------

(defn retry-after-ms
  "How long the provider asked us to wait, from the response headers, or nil.

  Handles `retry-after` in seconds and the `x-ratelimit-reset-*` family that
  several OpenAI-compatible providers send instead. Bounded, because the value
  is the provider's opinion and the ceiling is ours."
  [headers]
  (let [h (fn [k] (get headers k))
        secs (some-> (or (h "retry-after") (h "Retry-After")) str/trim parse-long)
        reset (some-> (or (h "x-ratelimit-reset-requests")
                          (h "x-ratelimit-reset-tokens"))
                      str/trim
                      (str/replace #"[sm]$" "")
                      parse-long)]
    (when-let [s (or secs reset)]
      (min max-backoff-ms (* 1000 (max 0 s))))))

(defn classify
  "Decide what to do about a non-2xx response.

  :retry — transient, try again. :fatal — do not retry, the answer will not
  change. Anything unrecognized is fatal, because retrying an error we do not
  understand spends budget to learn nothing."
  [adapter status body]
  (cond
    (and (= 429 status) (adapter/usage-cap? adapter status body)) :fatal
    (= 429 status) :retry
    (>= status 500) :retry
    (= 408 status) :retry
    :else :fatal))

(defn- backoff-ms
  "2s, 8s, 32s. Overridden by whatever the provider asked for."
  [attempt headers]
  (or (retry-after-ms headers)
      (min max-backoff-ms (long (* 2000 (Math/pow 4 attempt))))))

;; --- one call ---------------------------------------------------------------

(defn- decode [body]
  (try (json/read-str body :key-fn keyword) (catch Throwable _ nil)))

(defn- post-once [adapter config request]
  (let [url (adapter/chat-url adapter config)
        payload (json/write-str (adapter/chat-body adapter config request))
        started (System/currentTimeMillis)
        resp (http/post url {:headers (merge (adapter/auth-headers adapter config)
                                             {"Content-Type" "application/json"})
                             :body payload
                             :socket-timeout (:timeout-ms config default-timeout-ms)
                             :conn-timeout 30000
                             :throw-exceptions false})
        elapsed (- (System/currentTimeMillis) started)
        status (:status resp)
        decoded (decode (:body resp))]
    (log/debug (adapter/display-name adapter) "responded" status "in" elapsed "ms")
    (if (<= 200 status 299)
      (if-let [err (adapter/error-message adapter decoded)]
        ;; Some providers return 200 with an error object in the body.
        {:outcome :fatal :error (str (adapter/display-name adapter) " API error: " err)}
        (if-let [parsed (adapter/parse-chat adapter decoded)]
          (let [merged (message/merge-reasoning (:content parsed) (:reasoning parsed))]
            (if (str/blank? merged)
              {:outcome :fatal
               :error (str (adapter/display-name adapter)
                           " returned neither content nor reasoning. This usually means"
                           " the model spent its entire output budget thinking; raise"
                           " :max-tokens or shorten the context.")}
              {:outcome :ok
               :response {:content merged
                          :finish-reason (:finish-reason parsed)
                          :usage (:usage parsed)
                          :elapsed-ms elapsed}}))
          {:outcome :fatal
           :error (str (adapter/display-name adapter)
                       " reply had no completion in it: "
                       (subs (str (:body resp)) 0 (min 300 (count (str (:body resp))))))}))
      {:outcome (classify adapter status decoded)
       :headers (:headers resp)
       :error (str (adapter/display-name adapter) " error " status
                   (when-let [m (adapter/error-message adapter decoded)] (str " — " m))
                   (when-not decoded
                     (str " — " (subs (str (:body resp))
                                      0 (min 300 (count (str (:body resp))))))))})))

;; --- the public surface -----------------------------------------------------

(defn chat
  "Send `messages` and return {:content :finish-reason :usage :elapsed-ms}.

  Throws ex-info with :provider and :attempts when every attempt failed. The
  loop is bounded in attempts and each attempt is bounded in wall clock, so a
  stuck provider costs a known amount rather than the run."
  ([adapter config messages] (chat adapter config messages nil))
  ([adapter config messages {:keys [max-tokens temperature max-retries]}]
   (let [request {:messages (message/prepare messages)
                  :max-tokens (or max-tokens (:max-tokens config))
                  :temperature (or temperature (:temperature config))}
         retries (or max-retries (:max-retries config) default-max-retries)]
     (loop [attempt 0, errors []]
       (let [result (try
                      (post-once adapter config request)
                      (catch Throwable e
                        ;; A transport failure — connection reset, TLS error,
                        ;; socket timeout — is the case retrying exists for.
                        {:outcome :retry :error (str "transport: " (ex-message e))}))
             errors (conj errors (:error result))]
         (cond
           (= :ok (:outcome result))
           (:response result)

           (or (= :fatal (:outcome result)) (>= attempt retries))
           (throw (ex-info (str (adapter/display-name adapter) " call failed: "
                                (last errors))
                           {:provider (adapter/id adapter)
                            :attempts (inc attempt)
                            :errors errors}))

           :else
           (let [wait (backoff-ms attempt (:headers result))]
             (log/warn (adapter/display-name adapter) "attempt" (inc attempt)
                       "failed, retrying in" wait "ms:" (:error result))
             (Thread/sleep wait)
             (recur (inc attempt) errors))))))))

(defn list-models
  "Model ids the endpoint advertises, or [] when it has no such endpoint."
  [adapter config]
  (if-let [url (adapter/models-url adapter config)]
    (let [resp (http/get url {:headers (adapter/auth-headers adapter config)
                              :socket-timeout 30000
                              :throw-exceptions false})]
      (if (<= 200 (:status resp) 299)
        (adapter/parse-models adapter (decode (:body resp)))
        []))
    []))
