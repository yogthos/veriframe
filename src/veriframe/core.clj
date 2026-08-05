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

  ## nREPL load order, previously load bearing, now not

  Loading jolt.nrepl before any TLS handshake used to leave jolt.http-client
  unable to complete one for the rest of the process, so this namespace warmed
  TLS against the provider first and only then loaded nREPL through a runtime
  `require`. That is fixed upstream: jolt.nrepl calls (ffi/load-library) at ns
  load to bind sockets, which loads the running process's own symbols, and on
  macOS the process image transitively links LibreSSL — so the SSL_* symbols
  came from a mix of LibreSSL and OpenSSL and the first call through it faulted.
  jolt.http.tls now loads libcrypto and libssl itself immediately before its
  bindings resolve, so whoever loads first no longer decides.

  nREPL is therefore a plain static require again, which also puts it in a
  `jolt build` binary — the runtime require was the reason it never started
  there. `veriframe.smoke/nrepl-load-order-check` stays as the regression guard.

  `warm-tls!` survives on its own merit: it validates at boot that the provider
  is reachable and the key works, which the TypeScript harness also does. It is
  no longer ordering-critical."
  (:require [clojure.tools.logging :as log]
            ;; Statically required, both so the ordering bug stays fixed in the
            ;; open rather than by accident and so `jolt build` reaches it.
            [jolt.nrepl]
            [nrepl.middleware]
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
  a TLS handshake early, which used to be what kept https working for the rest
  of the process and is now merely a warm cache.

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

  jolt.nrepl is required statically in the ns form; see the namespace docstring
  for why it no longer has to wait for a TLS handshake."
  [port]
  (jolt.host/block-sigint)
  (try
    (let [stop (jolt.nrepl/start port ['nrepl.middleware/default-middleware])]
      (jolt.host/add-shutdown-hook stop)
      stop)
    (catch Throwable e
      ;; Not fatal — the harness serves without an editor attached.
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
