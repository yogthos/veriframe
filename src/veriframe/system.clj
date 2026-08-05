;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.system
  "The one place long-lived resources live, so everything else can be a
  function that an editor redefines against a running process.

  The rule this namespace exists to enforce: resources that are expensive to
  recreate go in `system` behind start!/stop!; logic does not. A swipl session,
  a Lean REPL that spends thirty seconds importing Mathlib, the database
  connection, and the HTTP server are resources. Gate definitions, prompts,
  tool methods, and parsers are logic, and reloading their namespace mid-run is
  supposed to work.

  `defonce` so reloading this namespace from a connected editor does not drop
  the handles to a server that is still listening."
  (:require [clojure.tools.logging :as log]
            ;; installs the java.time.* host shim tools.logging's timestamp
            ;; formatter resolves against; must load before the first log call
            [jolt.time]
            [jolt.http.platform :as platform]
            [ring-chez.adapter :as adapter]
            [veriframe.config :as config]
            [veriframe.engine.lean-pool :as lean-pool]
            [veriframe.llm.registry :as registry]
            [veriframe.store.db :as db]))

(defonce system (atom nil))

(defn started? [] (some? @system))

(defn config [] (:config @system))

(defn conn
  "The single writer connection. See store.db for why there is only one."
  []
  (:conn @system))

(defn adapter
  "The provider adapter for the configured provider."
  []
  (registry/adapter-for (get-in @system [:config :llm :provider])))

(defn start!
  "Bring the system up. `overrides` is merged into the config, so a REPL
  session can do (start! {:db {:path \":memory:\"} :http {:port 3999}}).

  The handler is passed IN as a var rather than resolved here, because
  veriframe.server requires this namespace and resolving it dynamically would
  invert the dependency. It also has to be static: `jolt build` embeds what it
  can reach statically, and a `requiring-resolve` here left the entire server
  and engine subtree out of the binary, which then failed at startup trying to
  compile namespaces off source roots that do not exist in an image.

  Vars are callable and deref on each call, so redefining
  veriframe.server/handler in a connected editor still takes effect on the
  next request."
  ([handler] (start! handler nil))
  ([handler overrides]
   (when (started?)
     (throw (ex-info "system already started; call stop! first" {})))
   (let [cfg (config/load-config overrides)
         ;; Process-wide, and set here rather than in core so that every entry
         ;; point gets it: the tests, the benchmark runner and a REPL session
         ;; all bring the system up through start! without going through -main.
         _ (platform/set-max-response-ms! (get-in cfg [:llm :max-response-ms]))
         c (db/open! (get-in cfg [:db :path]))
         server (adapter/run-server handler {:port (get-in cfg [:http :port])})]
     (reset! system {:config cfg :conn c :server server})
     (log/info "veriframe up on port" (get-in cfg [:http :port])
               "provider" (get-in cfg [:llm :provider])
               "model" (get-in cfg [:llm :model])
               "db" (get-in cfg [:db :path]))
     ;; After the server is listening and the system is registered, because
     ;; warming is slow and must not hold up /health. It returns immediately;
     ;; the imports run on background threads.
     (when (get-in cfg [:warmup :lean?])
       (lean-pool/warm! (get-in cfg [:engines :lean])
                        (get-in cfg [:warmup :sessions])))
     :started)))

(defn stop!
  "Tear the system down. Best effort per resource: one failing close must not
  strand the others, which is the whole reason the RAX manager could always
  stop the Lisp task regardless of what the agent believed."
  []
  (when-let [s @system]
    (doseq [[label f] [["http server" #(adapter/stop-server (:server s))]
                       ;; Before the database, because a warmed Lean session is
                       ;; a subprocess holding gigabytes; leaking one per restart
                       ;; is how a dev session ends up with six orphaned repls.
                       ["warm Lean sessions" lean-pool/shutdown!]
                       ["database" #(db/close (:conn s))]]]
      (try (f) (catch Throwable e (log/warn "stopping" label "failed:" (ex-message e)))))
    (reset! system nil)
    :stopped))

(defn restart!
  ([handler] (restart! handler nil))
  ([handler overrides] (stop!) (start! handler overrides)))
