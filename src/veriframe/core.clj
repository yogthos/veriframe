(ns veriframe.core
  "Entry point: bring the system up, validate the provider, start nREPL, park.

  The workflow this is built for is to leave the process running and develop
  against it from an editor. The slow parts of this harness are exactly the
  parts you never want to restart: a swipl session, a Lean REPL that spends
  thirty seconds importing Mathlib, and a provider call that takes minutes.

      jolt dev                 # or: jolt serve, without the dev/test trees
      # connect CIDER / Calva / Cursive to the port in .nrepl-port, then:
      (require '[veriframe.system :as system])
      (system/config)
      (system/restart! {:http {:port 3999}})

  Redefining a namespace other than veriframe.system takes effect immediately;
  the server holds the handler var, not the function it currently contains.

  ## Why nREPL is loaded lazily, and what it costs

  Loading jolt.nrepl into a process that has not yet completed a TLS handshake
  leaves jolt.http-client unable to complete one afterwards: every https call
  throws an opaque Chez condition, while plain http keeps working. A prior
  SUCCESSFUL https call immunizes the process; a failed one, a plain-http call,
  and constructing an SSL_CTX by hand all do not. So the order is load bearing
  — warm the TLS path against the provider, and only then load and start nREPL.
  `veriframe.smoke/nrepl-load-order-check` is the regression guard.

  This conflicts with `jolt build`, which embeds what it can reach statically
  and therefore cannot embed a namespace only reached by a runtime `require`.
  The interpreted path is the one that has to work, so it wins: nREPL loads
  lazily and simply does not start in a built binary. Nothing is lost there
  that matters, because a built binary cannot make https calls at all — see
  PLAN.md, Phase 7. Both are reported upstream against jolt-lang/jolt."
  (:require [clojure.tools.logging :as log]
            [veriframe.llm.client :as llm]
            ;; Statically required so the build reaches the whole server and
            ;; engine subtree; core is the only place that can, since server
            ;; requires system.
            [veriframe.server :as server]
            [veriframe.system :as system]))

(defn warm-tls!
  "Make one real HTTPS request to the configured provider.

  Two jobs in one call. It validates that the provider is reachable and the
  key works, which the TypeScript harness also does at boot. And it completes
  a TLS handshake before jolt.nrepl loads, which is what keeps https working
  for the rest of the process.

  Never throws. A harness whose provider is down should still come up: the
  engines need no network, and so does most development."
  [config]
  (let [{:keys [provider model]} (:llm config)]
    (try
      (let [models (llm/list-models (system/adapter) (:llm config))]
        (cond
          (empty? models)
          (do (log/warn "provider" provider "listed no models; TLS is warm but the"
                        "endpoint may be misconfigured")
              :unverified)

          (some #{model} models)
          (do (log/info "provider" provider "reachable," model "available") :ok)

          :else
          (do (log/warn "provider" provider "is reachable but does not list" model
                        "— available:" (pr-str (take 10 models)))
              :model-missing)))
      (catch Throwable e
        (log/warn "provider warm-up failed:" (ex-message e)
                  "— the harness will start, but https is expected to stay broken"
                  "in this process once nREPL loads")
        :failed))))

(defn start-nrepl!
  "Start nREPL with the session/completion/lookup middleware on a background
  thread. SIGINT is blocked on this thread first so ^C lands here rather than
  on an accept loop parked in a foreign recv.

  Loaded at call time rather than in the ns form; see the namespace docstring
  for why the load has to happen after the TLS warm-up, and what that costs a
  built binary."
  [port]
  (jolt.host/block-sigint)
  (try
    (require 'jolt.nrepl 'nrepl.middleware)
    (let [start (resolve 'jolt.nrepl/start)
          stop (start port ['nrepl.middleware/default-middleware])]
      (jolt.host/add-shutdown-hook stop)
      stop)
    (catch Throwable e
      ;; Expected in a built binary: no source roots, so the runtime require
      ;; finds nothing. Not fatal — the harness serves without an editor
      ;; attached.
      (log/warn "nREPL not started:" (ex-message e))
      nil)))

(defn -main [& _args]
  (system/start! #'server/handler)
  (let [cfg (system/config)
        nrepl-port (get-in cfg [:nrepl :port])
        warm (warm-tls! cfg)]
    (jolt.host/add-shutdown-hook system/stop!)
    (start-nrepl! nrepl-port)
    (println)
    (println "veriframe")
    (println (str "  http   http://127.0.0.1:" (get-in cfg [:http :port]) "/health"))
    (println (str "  nrepl  127.0.0.1:" nrepl-port))
    (println (str "  model  " (name (get-in cfg [:llm :provider]))
                  " / " (get-in cfg [:llm :model])
                  " (" (name warm) ")"))
    (println)
    ;; Park. The server and nREPL run on worker threads; the shutdown hooks
    ;; close them. Returning here lets the launcher tear the process down.
    (jolt.host/park-until-interrupt)
    (system/stop!)))
