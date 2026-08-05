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

(ns veriframe.bench.runner
  "Run the registry and check each problem's harness-level expectation.

  The expectations are checked against the JOURNAL, not against a transcript.
  `:answered`, `:refused`, `:gate`, `:never-gate` and `:status` are all SQL
  questions, so a result is a fact rather than a reading, and a probe that was
  supposed to provoke a gate either provoked it or did not.

  The mechanism check comes from dirge PR 739 and is enforced rather than
  advised: an arm whose gate never fired did not exercise the mechanism it was
  written for, and its other numbers are noise. A probe that expected a gate
  and did not get one is reported as INERT rather than as a pass, because a
  silent guard and a working guard look identical from the outside."
  (:require [clojure.string :as str]
            [jdbc.core :as jdbc]
            [veriframe.agent.beam :as beam]
            [veriframe.bench.beam :as metrics]
            [veriframe.bench.problems :as problems]
            [veriframe.config :as config]
            [veriframe.engine.lean-repl :as lean-repl]
            [veriframe.llm.registry :as registry]
            [veriframe.store.db :as db]))

(defn- gates-fired [conn run-id]
  (set (map :gate (db/fetch conn ["SELECT DISTINCT gate FROM gate_firings WHERE run_id = ?"
                                    run-id]))))

(defn- statuses [conn run-id]
  (set (map :claim_status
            (db/fetch conn ["SELECT DISTINCT claim_status FROM artifacts WHERE run_id = ?"
                              run-id]))))

(defn check
  "Evaluate one problem's expectations. Returns [verdict reasons]."
  [conn run-id expect result]
  (let [fired (gates-fired conn run-id)
        sts (statuses conn run-id)
        answered (= :completed (:status result))
        answer (str (:answer result))
        fails (cond-> []
                (and (:answered expect) (not answered))
                (conj "expected a verified answer, got none")

                (and (:refused expect) answered)
                (conj (str "SHIPPED an answer for a problem with none: " answer))

                (and (:contains expect) answered
                     (not (str/includes? (str/lower-case answer)
                                          (str/lower-case (:contains expect)))))
                (conj (str "the answer does not mention " (pr-str (:contains expect))))

                (and (:status expect) (not (contains? sts (:status expect))))
                (conj (str "no artifact with claim_status " (:status expect)))

                (and (:never-gate expect) (contains? fired (name (:never-gate expect))))
                (conj (str "gate " (:never-gate expect) " fired and should not have")))
        ;; Separate from a failure on purpose: a probe that did not provoke its
        ;; gate has not proven the gate broken, it has proven the probe did not
        ;; exercise it. Reporting that as a pass is the lie worth avoiding.
        inert (when (and (:gate expect) (not (contains? fired (name (:gate expect)))))
                (str "gate " (:gate expect) " never fired — the probe did not exercise it"))]
    (cond
      (seq fails) [:fail fails]
      inert [:inert [inert]]
      :else [:pass []])))

(defn run-one
  [{:keys [conn cfg adapter max-turns beam-width]} id {:keys [statement expect]}]
  (let [started (System/currentTimeMillis)
        result (try
                 (beam/run! {:conn conn :config cfg :llm-adapter adapter
                             :llm-config (:llm cfg) :problem statement
                             :max-turns max-turns :beam-width beam-width})
                 (catch Throwable e {:status :error :error (ex-message e)}))]
    (if (= :error (:status result))
      {:id id :verdict :error :reasons [(:error result)]}
      (let [[verdict reasons] (check conn (:run-id result) expect result)]
        {:id id :verdict verdict :reasons reasons
         :run-id (:run-id result)
         :wall-ms (- (System/currentTimeMillis) started)
         :metrics (metrics/run-metrics conn (:run-id result))}))))

(defn- report [rows]
  (println)
  (println (format "%-28s %-7s %6s %6s %7s  %s"
                   "problem" "verdict" "cost" "conf" "stuck" "notes"))
  (doseq [r rows]
    (println (format "%-28s %-7s %6s %6s %6s%%  %s"
                     (:id r) (name (:verdict r))
                     (or (get-in r [:metrics :turns]) "-")
                     (or (get-in r [:metrics :confirmed]) "-")
                     (if-let [s (get-in r [:metrics :stuck-fraction])]
                       (format "%.0f" (* 100.0 s)) "-")
                     (str/join "; " (:reasons r)))))
  (let [f (frequencies (map :verdict rows))]
    (println)
    (println (format "%d passed, %d failed, %d inert, %d errored"
                     (get f :pass 0) (get f :fail 0)
                     (get f :inert 0) (get f :error 0)))
    (when (pos? (get f :inert 0))
      (println "An inert probe is not a pass: the gate it targets never fired,")
      (println "so nothing about that mechanism was exercised."))
    (println)
    (println "Gates that never fired across the whole sweep are either dead or")
    (println "guarding something no probe provokes. Both are worth knowing."))
  rows)

(defn run-suite
  "Run selected problems. `ids` defaults to everything the toolchain supports."
  ([] (run-suite nil))
  ([ids] (run-suite ids {}))
  ([ids {:keys [max-turns beam-width db-path concurrency]
         :or {max-turns 14 beam-width 3 db-path "bench-suite.sqlite3"
              concurrency 3}}]
   (let [cfg (config/load-config {:db {:path db-path}})
         conn (db/open! db-path)
         lean? (lean-repl/available? (get-in cfg [:engines :lean]))
         chosen (cond-> (if (seq ids)
                          (select-keys problems/problems ids)
                          problems/problems)
                  ;; Skipping is honest; pretending a Lean problem passed
                  ;; without a toolchain is not.
                  (not lean?) (->> (remove #(contains? (:engines (val %)) :lean))
                                   (into {})))
         ctx {:conn conn :cfg cfg
              :adapter (registry/adapter-for (get-in cfg [:llm :provider]))
              :max-turns max-turns :beam-width beam-width}]
     (try
       (println (format "benchmark: %d problems, width %d, %d turns, %d at a time, model %s"
                        (count chosen) beam-width max-turns concurrency
                        (get-in cfg [:llm :model])))
       (when-not lean?
         (println "Lean toolchain absent — Lean problems skipped, not failed."))
       ;; Batched rather than sequential or fully parallel. Sequential is two
       ;; hours; all fourteen at once is forty-two concurrent branches against
       ;; one provider, which measures rate limiting rather than the harness.
       (report (vec (mapcat
                     (fn [batch]
                       (println "  batch:" (str/join ", " (map key batch)))
                       (mapv deref
                             (mapv (fn [[id p]] (future (run-one ctx id p))) batch)))
                     (partition-all (or concurrency 3) (sort-by key chosen)))))
       (finally (db/close conn))))))

(defn gate-coverage
  "Which gates the sweep ever exercised. A gate that never fires across the
  whole registry is either dead code or guarding a case the probe set does not
  provoke, and the fix differs."
  [db-path]
  (let [conn (db/open! db-path)]
    (try
      (db/fetch conn ["SELECT gate, count(*) AS fired,
                                sum(CASE WHEN outcome = 'met' THEN 1 ELSE 0 END) AS met
                         FROM gate_firings GROUP BY gate ORDER BY fired DESC"])
      (finally (db/close conn)))))
