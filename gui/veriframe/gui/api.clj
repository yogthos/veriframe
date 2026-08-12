;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui.api
  "The GUI's HTTP client over the run API, plus the journal poll loop.

  Deliberately toolkit-free — http-client and JSON only, no glimmer requires
  — so the headless test suite covers it without loading GTK, and so the
  model/view split stays honest: everything the GUI knows arrives through
  these functions, and everything it does goes back through them. The
  server neither knows nor cares that a GUI exists.

  Every call returns {:ok true :body ...} or {:ok false :error ...} — a
  dead or absent server is a value the UI renders, never a throw that
  takes the window down."
  (:require [clojure.data.json :as json]
            [jolt.http-client :as http]))

(def ^:private opts
  {:socket-timeout 10000 :conn-timeout 3000 :throw-exceptions false})

(defn- decode [s]
  (try (json/read-str (str s) :key-fn keyword) (catch Throwable _ nil)))

(defn- result [r]
  (if (<= 200 (:status r 0) 299)
    {:ok true :body (decode (:body r))}
    {:ok false :error (str "HTTP " (:status r)
                           (some->> (:body r) decode :error :message (str ": ")))}))

(defn- GET [base path]
  (try (result (http/get (str base path) opts))
       (catch Throwable e {:ok false :error (ex-message e)})))

(defn- POST
  ([base path body] (POST base path body nil))
  ([base path body socket-timeout-ms]
   (try (result (http/post (str base path)
                           (cond-> (assoc opts
                                          :headers {"Content-Type" "application/json"}
                                          :body (json/write-str (or body {})))
                             socket-timeout-ms (assoc :socket-timeout socket-timeout-ms))))
        (catch Throwable e {:ok false :error (ex-message e)}))))

(defn health [base] (GET base "/health"))

(defn list-runs [base] (GET base "/v1/runs"))

(defn run-detail [base run-id] (GET base (str "/v1/runs/" run-id)))

(defn journal-since
  "Everything after `cursor`, the polling UI's one read."
  ([base run-id cursor] (journal-since base run-id cursor 200))
  ([base run-id cursor limit]
   (GET base (str "/v1/runs/" run-id "/journal?since=" (or cursor 0)
                  "&limit=" limit))))

(defn branch-detail
  "Every turn and artifact for a branch, in full. Deliberately given a
  longer socket timeout than the rest: the response carries every result
  string and every encoding, which on a long run is hundreds of kilobytes
  and takes seconds. The GUI renders live activity from the event stream
  meanwhile, so this arriving late costs nothing."
  [base run-id branch-id]
  (try (result (http/get (str base "/v1/runs/" run-id "/branches/" branch-id)
                         (assoc opts :socket-timeout 45000)))
       (catch Throwable e {:ok false :error (ex-message e)})))

(def start-timeout-ms
  "How long to wait for POST /v1/runs.

  Deliberately longer than the 30s api.control/start-run! itself waits on
  (deref promised 30000). That endpoint does not answer when the run row is
  written — beam/run! opens every branch in the beam first and only then
  calls on-start — so a start can legitimately take tens of seconds, and how
  long depends on the beam width and on what else the machine is doing.

  Under the shared 10s default this reported a failure for a run that was
  starting normally: the row was already committed, so the user was told the
  run had failed AND left with a live run consuming provider spend, with the
  same request succeeding under curl. Any client bound tighter than the
  server's own budget has that bug; this one has to outlast it."
  40000)

(defn start-run!
  "Start a fresh run and return its id in the body.

  The server answers 200 with an `{:error ...}` body when the beam fails to
  come up inside its 30s window, so a plain 2xx is not proof a run exists;
  that case is folded to {:ok false} here rather than left for each caller
  to remember."
  [base body]
  (let [r (POST base "/v1/runs" body start-timeout-ms)]
    (if (and (:ok r) (get-in r [:body :error]))
      {:ok false :error (get-in r [:body :error])}
      r)))

(defn intervene!
  "A human directive, applied at the branch's next turn boundary. `branch-id`
  nil targets the whole run."
  [base run-id {:keys [branch-id kind payload]}]
  (POST base (str "/v1/runs/" run-id "/interventions")
        (cond-> {:kind (or kind "message") :payload payload}
          branch-id (assoc :branch_id branch-id))))

(defn abort! [base run-id]
  (POST base (str "/v1/runs/" run-id "/abort") {}))

(defn resume!
  "Resume a crashed run; with `max-turns`, extend an exhausted one's budget."
  ([base run-id] (resume! base run-id nil))
  ([base run-id max-turns]
   (POST base (str "/v1/runs/" run-id "/resume")
         (if max-turns {:max_turns max-turns} {}))))

;; --- the poll loop -----------------------------------------------------------

(def base-interval-ms 1500)
(def max-backoff-ms 30000)

(defn poll-step
  "Fold one journal fetch into the loop state {:cursor :interval-ms}.

  Pure, so the loop's whole policy is testable offline: events advance the
  cursor and reset the interval; an empty batch keeps both; a failure marks
  the state disconnected and doubles the interval up to the cap — the
  cursor survives an outage, so nothing is missed when the server returns."
  [{:keys [cursor interval-ms] :as state} {:keys [ok body]}]
  (if ok
    (let [events (vec (:events body))]
      (assoc state
             :cursor (or (:id (peek events)) cursor 0)
             :interval-ms base-interval-ms
             :connected? true
             :events events))
    (assoc state
           :events []
           :connected? false
           :interval-ms (min max-backoff-ms
                             (* 2 (max base-interval-ms
                                       (or interval-ms base-interval-ms)))))))

(defn start-poller!
  "Tail `run-id`'s journal on a background thread.

  Calls (on-events events) for every non-empty batch and (on-status state)
  after every step. glimmer marshals ratom writes made off the main thread
  onto the GTK loop itself, so callbacks may swap! UI state directly.
  Returns {:stop! (fn [])}; stopping is cooperative at the next wakeup."
  [{:keys [base run-id on-events on-status]}]
  (let [running (atom true)]
    (future
      (loop [state {:cursor 0 :interval-ms base-interval-ms}]
        (when @running
          (let [state (poll-step state (journal-since base run-id (:cursor state)))]
            (when (and on-events (seq (:events state)))
              (on-events (:events state)))
            (when on-status (on-status (dissoc state :events)))
            (Thread/sleep (:interval-ms state))
            (recur state)))))
    {:stop! (fn [] (reset! running false))}))
