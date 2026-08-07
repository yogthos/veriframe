;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui-graph-test
  "The scene-graph fold: journal events in, {nodes edges run} out. Pure, so
  the whole model is testable against event shapes recorded from real runs."
  (:require [clojure.test :refer [deftest testing is]]
            [veriframe.gui.graph :as graph]))

(def events
  [{:id 1 :branch_id nil :turn nil :kind "run-started" :data {:problem "p" :model "m"}}
   {:id 2 :branch_id nil :turn nil :kind "run-seeded" :data {:source "old-run" :artifacts 7}}
   {:id 3 :branch_id "B1" :turn nil :kind "branch-opened" :data {:parent nil}}
   {:id 4 :branch_id "B2" :turn nil :kind "branch-opened" :data {:parent nil}}
   {:id 5 :branch_id "B1" :turn 1 :kind "turn" :data {:tool "verify" :category "success"}}
   {:id 6 :branch_id "B1" :turn 1 :kind "thesis" :data {:goal "settle two sets"}}
   {:id 7 :branch_id "B1" :turn 2 :kind "artifact"
    :data {:kind "smt" :claim "c1" :claim-status "confirmed"}}
   {:id 8 :branch_id "B1" :turn 3 :kind "artifact"
    :data {:kind "smt" :claim "c2" :claim-status "refuted"}}
   {:id 9 :branch_id "B1" :turn 5 :kind "critic-score"
    :data {:progress 4 :momentum 5 :distinctness 3 :viability 5}}
   {:id 10 :branch_id "B2" :turn 5 :kind "cull-spared"
    :data {:scores {:progress 2 :momentum 2 :distinctness 5 :viability 3} :failures 3}}
   {:id 11 :branch_id "B1" :turn 6 :kind "fork-invite" :data {:progress 4}}
   {:id 12 :branch_id "B1.2" :turn 6 :kind "branch-opened" :data {:parent "B1"}}
   {:id 13 :branch_id "B2" :turn 8 :kind "branch-closed"
    :data {:status "culled" :reason "dominated by a sibling"}}
   {:id 14 :branch_id nil :turn nil :kind "run-finished" :data {:status "completed"}}])

(deftest fold-builds-the-scene-graph
  (let [g (graph/fold events)]
    (testing "nodes exist with status, thesis, confirmed counts"
      (is (= #{"seed" "B1" "B2" "B1.2"} (set (keys (:nodes g)))))
      (is (= :active (get-in g [:nodes "B1" :status])))
      (is (= :culled (get-in g [:nodes "B2" :status])))
      (is (= "dominated by a sibling" (get-in g [:nodes "B2" :reason])))
      (is (= "settle two sets" (get-in g [:nodes "B1" :thesis])))
      (is (= 1 (get-in g [:nodes "B1" :confirmed]))
          "refuted artifacts do not count as confirmed")
      ;; :turn tracks "turn" events only — in a real journal every artifact
      ;; rides alongside one, so this is the branch's model-turn clock.
      (is (= 1 (get-in g [:nodes "B1" :turn]))))
    (testing "critic scores and retention markers attach"
      (is (= 5 (get-in g [:nodes "B1" :critic :momentum])))
      (is (true? (get-in g [:nodes "B2" :spared?])))
      (is (= 6 (get-in g [:nodes "B1" :invited]))))
    (testing "edges: parent links plus seed provenance into the roots"
      (is (= #{["B1" "B1.2"] ["seed" "B1"] ["seed" "B2"]}
             (set (graph/edges g)))))
    (testing "run-level facts"
      (is (= "completed" (get-in g [:run :status])))
      (is (= 7 (get-in g [:run :seeded :artifacts]))))
    (testing "insertion order is stable for layout"
      (is (= ["B1" "B2" "B1.2"] (:order g))))))

(deftest layout-places-generations-in-columns
  (let [g (graph/fold events)
        pos (graph/layout g)]
    (testing "seed at depth 0, roots at 1, children at 2"
      (is (= 0.0 (first (pos "seed"))))
      (is (= 1.0 (first (pos "B1"))))
      (is (= 1.0 (first (pos "B2"))))
      (is (= 2.0 (first (pos "B1.2")))))
    (testing "siblings in one column get distinct y"
      (is (not= (second (pos "B1")) (second (pos "B2")))))
    (testing "without a seed, roots sit at depth 0"
      (let [pos (graph/layout (graph/fold (remove #(= "run-seeded" (:kind %))
                                                  events)))]
        (is (= 0.0 (first (pos "B1"))))))))

(deftest view-transform-round-trips-and-picks
  (let [positions {"a" [0.0 0.0] "b" [2.0 1.0] "c" [1.0 -1.0]}
        t (graph/fit positions 800 600 40)]
    (testing "every node lands inside the padded viewport"
      (doseq [[_ p] positions]
        (let [[px py] (graph/world->px t p)]
          (is (<= 40 px 760))
          (is (<= 40 py 560)))))
    (testing "px->world inverts world->px"
      (let [[wx wy] (graph/px->world t (graph/world->px t [2.0 1.0]))]
        (is (< (abs (- wx 2.0)) 1e-9))
        (is (< (abs (- wy 1.0)) 1e-9))))
    (testing "nearest node within radius, nil beyond it"
      (let [[px py] (graph/world->px t [2.0 1.0])]
        (is (= "b" (graph/nearest positions t [px py] 30)))
        (is (nil? (graph/nearest positions t [(+ px 500) py] 30)))))
    (testing "one node degenerate bbox still yields a usable transform"
      (let [t1 (graph/fit {"only" [0.0 0.0]} 800 600 40)
            [px py] (graph/world->px t1 [0.0 0.0])]
        (is (and (<= 0 px 800) (<= 0 py 600)))))))

(deftest fold-is-defensive-about-order-and-unknown-kinds
  (testing "an event for a branch never opened still lands somewhere"
    (let [g (graph/fold [{:id 1 :branch_id "BX" :turn 2 :kind "thesis"
                          :data {:goal "g"}}])]
      (is (= "g" (get-in g [:nodes "BX" :thesis])))))
  (testing "unknown kinds are ignored, not fatal"
    (is (map? (graph/fold [{:id 1 :kind "somekind-added-later" :data {}}])))))
