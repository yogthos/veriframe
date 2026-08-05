(ns veriframe.smoke
  "Phase 0 platform probes. Every check here corresponds to a stated risk in
  PLAN.md, and the point is to find out now rather than in the phase that
  depends on it.

      jolt -M:smoke

  Exits non-zero if any required probe fails. Lean is optional and reports
  as skipped when the toolchain is absent, since only Phase 5 needs it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [jdbc.core :as jdbc]
            [jolt.http-client :as http]
            [jolt.process :as p]
            [veriframe.config :as config]
            [veriframe.engine.lean-repl :as lean-repl]
            [veriframe.store.db :as db]
            [veriframe.system :as system]))

(def ^:private results (atom []))

(defn- record! [name status detail]
  (swap! results conj {:name name :status status :detail detail})
  (println (format "  %-7s %-34s %s"
                   (case status :pass "pass" :fail "FAIL" :skip "skip")
                   name
                   (or detail ""))))

(defmacro probe
  "Run `body`; it should return [status detail] or throw."
  [name & body]
  `(try
     (let [[st# detail#] (do ~@body)]
       (record! ~name st# detail#))
     (catch Throwable e#
       (record! ~name :fail (str (ex-message e#))))))

;; --- engines ----------------------------------------------------------------

(defn- z3-check [bin]
  (let [{:keys [out exit]} (p/sh {:in "(assert (= (+ 2 2) 5))\n(check-sat)\n"} bin "-in")]
    (if (and (zero? exit) (str/includes? out "unsat"))
      [:pass (str/trim out)]
      [:fail (str "exit " exit " out " (pr-str (str/trim out)))])))

(defn- swipl-check [bin]
  ;; clpfd is the library the relational problems need, so probe it rather
  ;; than plain swipl: a swipl without clpfd passes a naive version check.
  ;;
  ;; The two -g flags are not stylistic. #> and #< are operators clpfd
  ;; defines, and a single goal is read in full before it runs, so loading
  ;; the library and using it in one term is a syntax error. The persistent
  ;; session in Phase 1 has the same constraint: it must consult a bootstrap
  ;; file that loads clpfd before any clpfd term reaches the reader.
  (let [{:keys [out exit]} (p/sh bin "-q"
                                 "-g" "use_module(library(clpfd))"
                                 "-g" "X #> 2, X #< 4, label([X]), format(\"X=~w~n\",[X])"
                                 "-t" "halt")]
    (if (and (zero? exit) (str/includes? out "X=3"))
      [:pass "clpfd available"]
      [:fail (str "exit " exit " out " (pr-str (str/trim out)))])))

(defn- swipl-concurrency [bin n]
  ;; The Phase 4 beam holds one swipl session per branch, so concurrent
  ;; blocking pipe reads under Chez threads have to work. If this fails the
  ;; fallback is a queue per engine with branches concurrent only in their
  ;; provider calls.
  (let [fs (mapv (fn [i]
                   (future
                     (let [goal (str "X is " i " * 7, format(\"~w~n\",[X]), halt.")
                           {:keys [out exit]} (p/sh bin "-g" goal "-t" "halt")]
                       (and (zero? exit) (= (str (* i 7)) (str/trim out))))))
                 (range 1 (inc n)))]
    (if (every? deref fs)
      [:pass (str n " concurrent sessions agreed")]
      [:fail "at least one concurrent session returned the wrong answer"])))

(defn- lean-check
  "Actually import Mathlib and elaborate a theorem.

  This used to be (.exists (File. repl-bin)), so `pass lean repl` meant a file
  was on disk and nothing more. It stayed green through the entire period when
  every Lean call was failing, because the import blew a timeout it never
  measured. A probe that cannot fail the way the engine fails is not a probe.

  Slow — minutes — which is the honest cost of checking the thing that breaks."
  [cfg]
  (if-not (lean-repl/available? cfg)
    [:skip (str "no repl at " (:repl-bin cfg) " — run tools/setup-lean.sh")]
    (let [t0 (System/currentTimeMillis)
          s (lean-repl/create-session cfg)]
      (try
        (lean-repl/mathlib-env s)
        (let [ms (- (System/currentTimeMillis) t0)
              r (lean-repl/run-command s "theorem smoke (n : Nat) : n + 0 = n := by simp")]
          (if (:ok r)
            [:pass (str "Mathlib imported in " ms "ms, theorem elaborated")]
            [:fail (str "Mathlib imported but the theorem failed: " (pr-str (:errors r)))]))
        (catch Throwable e
          [:fail (str "after " (- (System/currentTimeMillis) t0) "ms: " (ex-message e))])
        (finally (lean-repl/dispose! s))))))

;; --- storage ----------------------------------------------------------------

(defn- sqlite-check []
  (let [c (db/open! ":memory:")]
    (try
      (let [v (db/schema-version c)
            tables (db/table-names c)]
        (if (and (pos? v) (every? (set tables) ["runs" "branches" "turns" "artifacts"
                                                "failures" "gate_firings"
                                                "interventions" "events"]))
          [:pass (str "user_version " v ", " (count tables) " tables")]
          [:fail (str "user_version " v ", tables " (pr-str tables))]))
      (finally (db/close c)))))

(defn- fts5-check []
  (let [c (db/connect ":memory:")]
    (try
      (if (db/fts5-available? c)
        (do (jdbc/execute! c "CREATE VIRTUAL TABLE t USING fts5(claim, reason)")
            (jdbc/execute! c ["INSERT INTO t VALUES (?, ?)"
                              "sidon set of size 24" "z3 returned unsat"])
            (let [rows (jdbc/fetch c ["SELECT claim FROM t WHERE t MATCH ?" "sidon"])]
              (if (= 1 (count rows))
                [:pass "match returned the row"]
                [:fail (str "match returned " (count rows) " rows")])))
        [:fail "libsqlite3 loaded by the FFI binding has no FTS5"])
      (finally (db/close c)))))

(defn- sqlite-concurrency [n]
  ;; Not a claim that concurrent writers are safe — a claim that the single
  ;; writer this design uses survives concurrent callers.
  (let [c (db/connect ":memory:")]
    (try
      (jdbc/execute! c "CREATE TABLE t (id INTEGER PRIMARY KEY, v TEXT)")
      (let [lock (Object.)
            fs (mapv (fn [i]
                       (future
                         (dotimes [j 20]
                           (locking lock
                             (jdbc/execute! c ["INSERT INTO t (v) VALUES (?)"
                                               (str i "-" j)])))))
                     (range n))]
        (run! deref fs)
        (let [n* (-> (jdbc/fetch-one c "SELECT count(*) AS n FROM t") :n)]
          (if (= n* (* n 20))
            [:pass (str n* " rows from " n " writers")]
            [:fail (str "expected " (* n 20) " rows, got " n*)])))
      (finally (db/close c)))))

;; --- network ----------------------------------------------------------------

(defn- provider-check [{:keys [base-url api-key model timeout-ms]}]
  (if (str/blank? api-key)
    [:skip "no API key in the environment"]
    (let [resp (http/get (str base-url "/models")
                         {:headers {"Authorization" (str "Bearer " api-key)}
                          :socket-timeout timeout-ms})]
      (if (and (= 200 (:status resp)) (str/includes? (:body resp) model))
        [:pass (str model " listed")]
        [:fail (str "status " (:status resp) ", model " model
                    (when-not (str/includes? (:body resp) model) " NOT listed"))]))))

(defn- long-request-check [{:keys [base-url api-key model timeout-ms]}]
  ;; A five-minute TLS read is the shape of every real provider call here, and
  ;; clj-http-lite over jolt.ffi sockets has not been exercised at that
  ;; duration. One real completion is the cheapest honest probe.
  (if (str/blank? api-key)
    [:skip "no API key in the environment"]
    (let [start (System/currentTimeMillis)
          resp (http/post (str base-url "/chat/completions")
                          {:headers {"Authorization" (str "Bearer " api-key)}
                           :content-type :json
                           :socket-timeout timeout-ms
                           :body (json/write-str
                                  {:model model
                                   :max_tokens 1200
                                   :messages [{:role "user"
                                               :content "Count from 1 to 300, one number per line, nothing else."}]})})
          ms (- (System/currentTimeMillis) start)]
      (if (= 200 (:status resp))
        [:pass (str ms "ms, " (count (:body resp)) " bytes")]
        [:fail (str "status " (:status resp))]))))

(defn- nrepl-load-order-check []
  ;; Regression guard for the load-order bug in veriframe.core's ns form.
  ;; Requiring jolt.nrepl before jolt.http-client leaves the process unable to
  ;; complete a TLS handshake at all. It has to run in a subprocess, because
  ;; by the time this namespace is evaluated both are already loaded here in
  ;; whatever order the smoke run used.
  (let [code (str "(require 'veriframe.core 'veriframe.system)"
                  "(require (quote veriframe.server))(veriframe.system/start! (var veriframe.server/handler) {:http {:port 3993} :db {:path \":memory:\"}})"
                  "(veriframe.core/warm-tls! (veriframe.system/config))"
                  "(require 'jolt.nrepl)"
                  "(require '[jolt.http-client :as h])"
                  "(println :probe (try (:status (h/get \"https://example.com\"))"
                  "                     (catch Throwable e :tls-broken)))"
                  "(veriframe.system/stop!)")
        {:keys [out timeout]} (p/sh {:timeout-ms 180000} "jolt" "-e" code)]
    (cond
      timeout [:fail "the subprocess did not finish"]
      (str/includes? (str out) ":probe 200")
      [:pass "https survives the nREPL load in the real startup order"]
      (str/includes? (str out) ":tls-broken")
      [:fail (str "TLS is broken once jolt.nrepl loads. The warm-up in"
                  " veriframe.core/warm-tls! must complete a real https handshake"
                  " BEFORE nREPL is required.")]
      :else [:fail (str "unexpected output: " (str/trim (str out)))])))

;; --- server -----------------------------------------------------------------

(defn- server-concurrency-check []
  ;; The vendored adapter change: upstream serves one connection at a time on
  ;; the accept thread, so a multi-minute beam would block /health. /slow
  ;; sleeps three seconds; /health must answer well inside that.
  (let [port 3987]
    (system/start! (requiring-resolve 'veriframe.server/handler)
                   {:http {:port port} :db {:path ":memory:"}})
    (try
      (let [base (str "http://127.0.0.1:" port)
            slow (future (http/get (str base "/slow?ms=3000") {:socket-timeout 10000}))
            _ (Thread/sleep 300)
            start (System/currentTimeMillis)
            health (http/get (str base "/health") {:socket-timeout 10000})
            ms (- (System/currentTimeMillis) start)]
        @slow
        (cond
          (not= 200 (:status health)) [:fail (str "/health returned " (:status health))]
          (> ms 2000) [:fail (str "/health waited " ms "ms behind /slow — server is serialized")]
          :else [:pass (str "/health answered in " ms "ms while /slow was running")]))
      (finally (system/stop!)))))

;; --- driver -----------------------------------------------------------------

(defn run []
  (reset! results [])
  (let [cfg (config/load-config)
        engines (:engines cfg)]
    (println "veriframe phase 0 probes\n")

    (println "engines")
    (probe "z3" (z3-check (get-in engines [:z3 :bin])))
    (probe "swipl + clpfd" (swipl-check (get-in engines [:swipl :bin])))
    (probe "swipl x5 concurrent" (swipl-concurrency (get-in engines [:swipl :bin]) 5))
    (probe "lean repl" (lean-check (:lean engines)))

    (println "\nstorage")
    (probe "sqlite migrate" (sqlite-check))
    (probe "sqlite fts5" (fts5-check))
    (probe "sqlite 5 writers" (sqlite-concurrency 5))

    (println "\nnetwork")
    (probe "provider reachable" (provider-check (:llm cfg)))
    (probe "long completion" (long-request-check (:llm cfg)))
    (probe "https after nrepl load" (nrepl-load-order-check))

    (println "\nserver")
    (probe "concurrent requests" (server-concurrency-check))

    (let [failed (filter #(= :fail (:status %)) @results)
          skipped (filter #(= :skip (:status %)) @results)]
      (println)
      (println (format "%d passed, %d failed, %d skipped"
                       (count (filter #(= :pass (:status %)) @results))
                       (count failed)
                       (count skipped)))
      (empty? failed))))

(defn -main [& _]
  (if (run)
    (System/exit 0)
    (System/exit 1)))
