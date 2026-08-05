;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU General Public License as
;; published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public
;; License along with this program. If not, see
;; <https://www.gnu.org/licenses/>.

(ns veriframe.server
  "The HTTP surface.

  Routes are matched against a vector of [method path-or-pattern handler]
  rather than through a router library. There are a dozen of them, and a
  dependency that needs its :clj reader branches switched on costs more to load
  than it saves.

  This namespace is pure logic: redefining `handler` against a running process
  takes effect on the next request. See veriframe.system."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [veriframe.agent.gates :as gates]
            [veriframe.api.control :as control]
            [veriframe.api.openai :as openai]
            [veriframe.api.runs :as api-runs]
            [veriframe.config :as config]
            [veriframe.engine.lean-pool :as lean-pool]
            [veriframe.engine.lean-repl :as lean-repl]
            [veriframe.engine.proc :as proc]
            [veriframe.store.db :as db]
            [veriframe.system :as system]))

(defn json-response
  ([body] (json-response 200 body))
  ([status body]
   {:status status
    :headers {"Content-Type" "application/json"}
    :body (json/write-str body)}))

(defn- body-json [req]
  (let [b (:body req)]
    (when b
      (try (json/read-str (if (string? b) b (slurp b)) :key-fn keyword)
           (catch Throwable _ nil)))))

(defn- query-param [req k]
  (some-> (:query-string req)
          (str/split #"&")
          (->> (keep #(let [[a v] (str/split % #"=" 2)] (when (= a k) v)))
               first)))

(defn- long-param [req k] (some-> (query-param req k) parse-long))

(defn- ctx [] {:conn (system/conn) :config (system/config)})

;; --- handlers ---------------------------------------------------------------

(defn- health [_req]
  (let [cfg (system/config)]
    (json-response
     {:status "ok"
      :schema_version (db/schema-version (system/conn))
      ;; `lean` is installed-ness, which is not readiness: a Lean call is not
      ;; usable until Mathlib is imported, and that takes minutes. Reporting one
      ;; boolean conflated the two and read as green while every Lean call was
      ;; failing, so the warm counts are published next to it.
      :engines {:z3 (proc/available? (get-in cfg [:engines :z3 :bin]))
                :swipl (proc/available? (get-in cfg [:engines :swipl :bin]))
                :lean (lean-repl/available? (get-in cfg [:engines :lean]))}
      :lean {:installed (lean-repl/available? (get-in cfg [:engines :lean]))
             :warm_sessions (lean-pool/warmed-count)
             :warming (lean-pool/pending-count)}
      :active_runs (count @control/active)
      :config (config/redacted (select-keys cfg [:llm :run :db]))})))

(defn- models [_req]
  (let [{:keys [model provider]} (:llm (system/config))]
    (json-response {:object "list"
                    :data [{:id model :object "model" :owned_by (name provider)}]})))

(defn- chat-completions [req]
  (let [r (openai/chat-completion (ctx) (body-json req))]
    (json-response (or (:status r) 200) (:body r))))

(defn- gate-table [_req]
  (json-response {:gates (gates/describe) :thresholds (gates/config)}))

;; --- routing ----------------------------------------------------------------
;;
;; A route is [method pattern handler]. A pattern segment starting with ':'
;; binds; the bindings arrive under :path-params.

(defn- slow
  "Sleeps, so the smoke probe can prove /health still answers while a handler is
  busy. That is the property the vendored thread-per-connection change buys and
  the reason a multi-minute beam can share a process with a UI."
  [req]
  (let [ms (or (some-> (query-param req "ms") parse-long) 1000)]
    (Thread/sleep ms)
    (json-response {:slept_ms ms})))

(def routes
  [[:get "/health" #'health]
   [:get "/slow" #'slow]
   [:get "/v1/models" #'models]
   [:post "/v1/chat/completions" #'chat-completions]
   [:get "/v1/harness/gates" #'gate-table]
   [:get "/v1/runs" (fn [req] (json-response (api-runs/list-runs (system/conn)
                                                                 (long-param req "limit"))))]
   [:post "/v1/runs" (fn [req] (json-response (control/start-run! (ctx) (body-json req))))]
   [:get "/v1/runs/:id" (fn [req]
                          (if-let [r (api-runs/get-run (system/conn)
                                                       (get-in req [:path-params :id]))]
                            (json-response r)
                            (json-response 404 {:error {:message "no such run"}})))]
   [:get "/v1/runs/:id/journal"
    (fn [req] (json-response (api-runs/journal-tail (system/conn)
                                                    (get-in req [:path-params :id])
                                                    (long-param req "since")
                                                    (long-param req "limit"))))]
   [:get "/v1/runs/:id/branches/:branch"
    (fn [req] (let [{:keys [id branch]} (:path-params req)]
                (if-let [b (api-runs/branch-detail (system/conn) id branch)]
                  (json-response b)
                  (json-response 404 {:error {:message "no such branch"}}))))]
   [:post "/v1/runs/:id/interventions"
    (fn [req] (json-response (control/intervene! (system/conn)
                                                 (get-in req [:path-params :id])
                                                 (body-json req))))]
   [:post "/v1/runs/:id/abort"
    (fn [req] (json-response (control/abort! (system/conn)
                                             (get-in req [:path-params :id]))))]
   [:get "/v1/interventions/kinds" (fn [_] (json-response (control/kinds)))]])

(defn- match-path [pattern uri]
  (let [ps (str/split (str/replace pattern #"^/" "") #"/")
        us (str/split (str/replace (or uri "") #"^/" "") #"/")]
    (when (= (count ps) (count us))
      (reduce (fn [acc [p u]]
                (cond
                  (str/starts-with? p ":") (assoc acc (keyword (subs p 1)) u)
                  (= p u) acc
                  :else (reduced nil)))
              {} (map vector ps us)))))

(defn- match [{:keys [request-method uri]}]
  (some (fn [[m pattern h]]
          (when (= m request-method)
            (when-let [params (match-path pattern uri)]
              [h params])))
        routes))

(defn handler [req]
  (try
    (if-let [[h params] (match req)]
      (h (assoc req :path-params params))
      (json-response 404 {:error {:message (str "Not found: "
                                                (str/upper-case (name (:request-method req)))
                                                " " (:uri req))
                                  :type "not_found"}}))
    (catch Throwable e
      (json-response 500 {:error {:message (ex-message e) :type "internal_error"}}))))
