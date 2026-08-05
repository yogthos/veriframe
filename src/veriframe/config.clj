(ns veriframe.config
  "Runtime configuration, read from the environment once at startup.

  Provider selection mirrors the TypeScript harness: an explicit
  HARNESS_PROVIDER wins, otherwise the first provider whose API key is present.
  In-process GGUF inference is not carried over — point HARNESS_BASE_URL at any
  OpenAI-compatible endpoint (including llama-server) instead."
  (:require [clojure.string :as str]))

(def ^:private providers
  {:deepseek {:base-url "https://api.deepseek.com/v1"
              :key-env  "DEEPSEEK_API_KEY"
              ;; deepseek-v4-flash is the development and test model: cheap
              ;; enough to run the beam repeatedly. deepseek-v4-pro is the
              ;; second arm. Note the TypeScript default, deepseek-reasoner,
              ;; is no longer served by the API.
              :model    "deepseek-v4-flash"}
   :glm      {:base-url "https://open.bigmodel.cn/api/paas/v4"
              :key-env  "ZHIPU_API_KEY"
              :model    "glm-5.1"}
   :openai   {:base-url "https://api.openai.com/v1"
              :key-env  "OPENAI_API_KEY"
              :model    "gpt-4o"}
   ;; A local llama-server / vLLM / LM Studio OpenAI-compatible endpoint.
   :local    {:base-url "http://127.0.0.1:8080/v1"
              :key-env  nil
              :model    "local-model"}
   ;; Ollama's NATIVE api, so no /v1 suffix. See llm/adapter/ollama.clj for
   ;; why the native surface rather than Ollama's OpenAI-compatible one.
   :ollama   {:base-url "http://127.0.0.1:11434"
              :key-env  nil
              :model    "qwen3"}})

(defn- env [k] (let [v (jolt.host/getenv k)] (when-not (str/blank? v) v)))

(defn- env-long [k] (some-> (env k) parse-long))

(defn- detect-provider []
  (or (some-> (env "HARNESS_PROVIDER") str/lower-case keyword)
      (first (for [p [:deepseek :glm :openai]
                   :let [ke (:key-env (providers p))]
                   :when (env ke)]
               p))
      :local))

(defn load-config
  "Build the config map. `overrides` is merged last so tests and REPL sessions
  can point at a fake provider or an in-memory database without touching env."
  ([] (load-config nil))
  ([overrides]
   (let [provider (detect-provider)
         defaults (or (providers provider)
                      (throw (ex-info (str "Unknown HARNESS_PROVIDER: " provider)
                                      {:provider provider
                                       :known (keys providers)})))]
     (merge
      {:http     {:port (or (env-long "HARNESS_PORT") 3000)}
       :nrepl    {:port (or (env-long "HARNESS_NREPL_PORT")
                            (env-long "JOLT_NREPL_PORT")
                            7888)}
       :db       {:path (or (env "HARNESS_DB") "veriframe.sqlite3")}
       :llm      {:provider    provider
                  :base-url    (or (env "HARNESS_BASE_URL") (:base-url defaults))
                  :api-key     (some-> (:key-env defaults) env)
                  :model       (or (env "HARNESS_MODEL") (:model defaults))
                  :max-tokens  (or (env-long "HARNESS_MAX_TOKENS") 16384)
                  :temperature 0.7
                  :timeout-ms  (or (env-long "HARNESS_TIMEOUT_MS") 300000)}
       ;; Engine timeouts are sized so that killing a call means it was stuck,
       ;; not merely slow. A false kill costs the branch a turn AND records a
       ;; failure other branches will avoid retrying, so the expensive mistake
       ;; is cutting a call short, not waiting too long.
       :engines  {:z3         {:bin (or (env "HARNESS_Z3_BIN") "z3")
                               :timeout-ms (or (env-long "HARNESS_Z3_TIMEOUT_MS")
                                               120000)}
                  :swipl      {:bin (or (env "HARNESS_SWIPL_BIN") "swipl")
                               :timeout-ms (or (env-long "HARNESS_SWIPL_TIMEOUT_MS")
                                               120000)}
                  :lean       {:workspace (or (env "HARNESS_LEAN_WORKSPACE")
                                              "tools/lean-workspace")
                               :repl-bin  (or (env "HARNESS_LEAN_REPL_BIN")
                                              "tools/lean-repl/.lake/build/bin/repl")
                               ;; Per command, not per import — see below.
                               :timeout-ms (or (env-long "HARNESS_LEAN_TIMEOUT_MS")
                                               300000)
                               ;; `import Mathlib` alone, measured at 377927ms on
                               ;; an idle machine with a warm cache. Sharing one
                               ;; knob with the per-command timeout is what made
                               ;; every Lean call fail: sized to a tactic the
                               ;; import cannot finish, sized to the import a
                               ;; wedged tactic goes unnoticed for minutes.
                               :import-timeout-ms
                               (or (env-long "HARNESS_LEAN_IMPORT_TIMEOUT_MS")
                                   1200000)}}
       ;; Warming pays the Mathlib import at boot instead of inside a branch
       ;; turn. Off when the toolchain is absent, which is the common case.
       :warmup   {:lean?    (not= "0" (or (env "HARNESS_WARM_LEAN") "1"))
                  :sessions (or (env-long "HARNESS_LEAN_WARM_SESSIONS") 1)}
       :run      {:max-turns  (or (env-long "HARNESS_MAX_TURNS") 80)
                  :beam-width (or (env-long "HARNESS_BEAM_WIDTH") 5)}}
      overrides))))

(defn redacted
  "The config with the API key masked, for logging and for /health."
  [config]
  (cond-> config
    (get-in config [:llm :api-key])
    (assoc-in [:llm :api-key] "***")))
