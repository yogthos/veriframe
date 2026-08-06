;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.bench.beam
  "Phase 4's empirical check: does the beam earn its width?

  The original ships BEAM_WIDTH 5 and never compared it against one branch
  given the same total budget. Five concurrent branches is five provider calls
  per turn, so the honest comparison holds token spend roughly fixed: width N
  at T turns against width 1 at N*T turns.

  What this can and cannot say is worth stating up front. dirge measured a
  roughly 2x run-to-run noise floor on identical configurations, and every
  arm here runs at n of one or two. So a turn-count difference inside that band
  is reported as noise and nothing is concluded from it. What survives at n=1
  is structural: did a cull happen, did a fork happen, did one branch avoid an
  approach another had already disproven, and did either arm actually solve the
  problem. Those are the rows to read."
  (:require [clojure.string :as str]
            [jdbc.core :as jdbc]
            [veriframe.agent.beam :as beam]
            [veriframe.config :as config]
            [veriframe.llm.registry :as registry]
            [veriframe.store.db :as db]))

(defn run-metrics
  "Everything worth comparing, straight out of the journal. Free to collect,
  which is the point of appending as the run goes."
  [conn run-id]
  (let [turns (db/fetch conn ["SELECT branch_id, category FROM turns WHERE run_id = ?" run-id])
        arts (db/fetch conn ["SELECT branch_id, claim_status FROM artifacts WHERE run_id = ?" run-id])
        branches (db/fetch conn ["SELECT id, status FROM branches WHERE run_id = ?" run-id])
        gates (db/fetch conn ["SELECT gate, outcome FROM gate_firings WHERE run_id = ?" run-id])
        run (db/fetch-one conn ["SELECT status, final_answer, beam_width FROM runs WHERE id = ?" run-id])
        n-turns (count turns)]
    {:run-id run-id
     :status (:status run)
     :answered (boolean (:final_answer run))
     :beam-width (:beam_width run)
     :branches (count branches)
     :forked (count (filter #(str/includes? (:id %) ".") branches))
     :culled (count (filter #(= "culled" (:status %)) branches))
     :turns n-turns
     ;; The share of turns that produced no verified progress. One number to
     ;; watch across phases, and the one a steering change should move.
     :stuck-fraction (if (zero? n-turns) 0.0
                         (/ (double (count (remove #(= "success" (:category %)) turns)))
                            n-turns))
     :artifacts (count arts)
     :confirmed (count (filter #(= "confirmed" (:claim_status %)) arts))
     :existential (count (filter #(= "existential" (:claim_status %)) arts))
     :gates-fired (count gates)
     :gates-met (count (filter #(= "met" (:outcome %)) gates))
     :gates-open (count (filter (comp nil? :outcome) gates))}))

(defn- cross-branch-hits
  "Turns whose context carried another branch's failure. This is the beam's
  whole justification: if it never happens, the branches are independent runs
  sharing a bill."
  [conn run-id]
  (let [failures (db/fetch conn ["SELECT branch_id, claim FROM failures WHERE run_id = ?" run-id])]
    (count (for [f failures
                 :let [others (db/fetch conn
                                          ["SELECT count(*) AS n FROM turns
                                            WHERE run_id = ? AND branch_id != ?"
                                           run-id (:branch_id f)])]
                 :when (pos? (:n (first others)))]
             f))))

(defn- run-arm [label {:keys [conn cfg problem width max-turns]}]
  (println (format "  %-14s width=%d max-turns=%d …" label width max-turns))
  (let [started (System/currentTimeMillis)
        r (beam/run! {:conn conn :config cfg
                      :llm-adapter (registry/adapter-for (get-in cfg [:llm :provider]))
                      :llm-config (:llm cfg)
                      :problem problem :beam-width width :max-turns max-turns})
        m (assoc (run-metrics conn (:run-id r))
                 :arm label
                 :wall-ms (- (System/currentTimeMillis) started)
                 :shared-failures (cross-branch-hits conn (:run-id r)))]
    (println (format "  %-14s -> %s in %d turns, %.0fs"
                     label (name (:status r)) (:turns m) (/ (:wall-ms m) 1000.0)))
    m))

(defn- report [rows]
  (println)
  (println (format "%-10s %-11s %6s %6s %6s %7s %8s %7s %6s"
                   "arm" "status" "cost" "conf" "exist" "stuck" "branches" "culled" "forks"))
  (doseq [m (sort-by :beam-width rows)]
    (println (format "%-10s %-11s %6d %6d %6d %6.0f%% %8d %7d %6d"
                     (:arm m) (str (:status m)) (:turns m) (:confirmed m)
                     (:existential m) (* 100.0 (:stuck-fraction m)) (:branches m)
                     (:culled m) (:forked m))))
  (println)
  (println "cost is total branch-turns, i.e. provider calls.")
  (let [solved (filter :answered rows)]
    (if (empty? solved)
      (println "No arm shipped an answer, so this run says nothing about width.")
      (let [best (first (sort-by :turns solved))]
        (println (format "Cheapest arm that shipped: %s at %d provider calls."
                         (:arm best) (:turns best)))))
    (println (format "Cross-branch failure reuse by arm: %s"
                     (pr-str (into {} (map (juxt :arm :shared-failures) rows)))))
    (println "n=1 per arm against a ~2x noise floor: turn differences are not")
    (println "results. Read whether an arm shipped, and what it cost to."))
  rows)

(defn sweep-widths
  "Run the same problem at several beam widths and report what each bought.

  NOT budget-matched, deliberately. The obvious design — width N at T turns
  against width 1 at N*T turns — is confounded, because Phase 4 measured a
  floor: a branch needs roughly ten turns to get through thesis, verify, review
  and audit at all, so a wide arm squeezed under that floor is measuring the
  floor rather than the beam. Every arm therefore gets the same turns PER
  BRANCH, which is above the floor, and cost is reported rather than held
  fixed. Total branch-turns is the cost column, since each is one provider
  call.

  Arms run concurrently to keep wall clock sane, which makes :wall-ms a
  measure of contention rather than of the arm. Read turns and confirmations,
  not seconds."
  ([problem] (sweep-widths problem [1 2 4] 12))
  ([problem widths turns-per-branch] (sweep-widths problem widths turns-per-branch {}))
  ;; opts may carry {:share-artifacts? true}: whether cross-branch sharing of
  ;; confirmed artifacts makes width pay is the open question the flag exists
  ;; to answer, and this is the instrument that answers it. Run the sweep both
  ;; ways and compare confirmations per branch-turn.
  ([problem widths turns-per-branch opts]
   (let [cfg (config/load-config
              (cond-> {:db {:path "bench-beam.sqlite3"}}
                (contains? opts :share-artifacts?)
                (assoc-in [:run :share-artifacts?] (:share-artifacts? opts))))
         conn (db/open! (get-in cfg [:db :path]))]
     (try
       (println (format "width sweep: %s at %d turns per branch, n=1 per arm"
                        (pr-str widths) turns-per-branch))
       (println "cost scales with width; it is reported, not held fixed.")
       (report (mapv deref
                     (mapv (fn [w]
                             (future
                               (run-arm (str "width-" w)
                                        {:conn conn :cfg cfg :problem problem
                                         :width w :max-turns turns-per-branch})))
                           widths)))
       (finally (db/close conn))))))
