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
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
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
      (is (= #{"seed" "B1" "B2" "B1.2"}
             (set (keep (fn [[id n]] (when (not= :artifact (:kind n)) id))
                        (:nodes g))))
          "the branch topology, with artifact nodes set aside")
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
      (is (= "verify" (get-in g [:nodes "B1" :tool]))
          "the tool drives the node's shape")
      (is (true? (get-in g [:nodes "B2" :spared?])))
      (is (= 6 (get-in g [:nodes "B1" :invited]))))
    (testing "edges: parent links plus seed provenance into the roots"
      (is (= #{["B1" "B1.2"] ["seed" "B1"] ["seed" "B2"]
               ["B1" "B1@2"] ["B1@2" "B1@3"]}
             (set (graph/edges g)))
          "branch links, seed provenance, and the artifact chain"))
    (testing "run-level facts"
      (is (= "completed" (get-in g [:run :status])))
      (is (= 7 (get-in g [:run :seeded :artifacts]))))
    (testing "insertion order is stable for layout"
      (is (= ["B1" "B2" "B1.2"]
             (vec (remove #(str/includes? % "@") (:order g))))))))

(deftest artifacts-chain-forward-off-their-branch
  ;; The beam is flat — branches almost never fork — so a graph of branch
  ;; topology alone never grows. The work does: each verification attempt
  ;; hangs off the previous one, so the run's reasoning reads left to right.
  (let [g (graph/fold events)
        ids (set (keys (:nodes g)))]
    (testing "each artifact is a node, chained to the previous on that branch"
      (is (contains? ids "B1@2"))
      (is (contains? ids "B1@3"))
      (is (= "B1" (get-in g [:nodes "B1@2" :parent])) "the first hangs off the branch")
      (is (= "B1@2" (get-in g [:nodes "B1@3" :parent])) "the next builds on it"))
    (testing "an artifact node carries what it claimed and how it went"
      (is (= :artifact (get-in g [:nodes "B1@2" :kind])))
      (is (= :smt (get-in g [:nodes "B1@2" :engine])))
      (is (= :confirmed (get-in g [:nodes "B1@2" :status])))
      (is (= :refuted (get-in g [:nodes "B1@3" :status])))
      (is (= "c1" (get-in g [:nodes "B1@2" :claim]))))
    (testing "layout puts the chain in successive columns"
      (let [pos (graph/layout g)]
        (is (< (first (pos "B1")) (first (pos "B1@2")) (first (pos "B1@3"))))))
    (testing "branch nodes stay branch-kinded"
      (is (= :branch (get-in g [:nodes "B1" :kind]))))))

(deftest live-activity-comes-from-the-event-stream
  ;; What a branch is doing must be knowable from the events the GUI already
  ;; has. The branch-detail endpoint returns every turn and artifact in full
  ;; — 268KB and six seconds on a long run — so an inspector that waits for
  ;; it shows nothing while a branch is most interesting.
  (let [g (graph/fold events)]
    (testing "each turn event lands in the branch's activity log"
      (let [a (get-in g [:nodes "B1" :activity])]
        (is (= 1 (count a)))
        (is (= {:turn 1 :tool "verify" :category "success"} (first a)))))
    (testing "the log is bounded so a long run cannot grow it without limit"
      (let [many (reduce (fn [acc i]
                           (graph/apply-event acc {:kind "turn" :branch_id "B1"
                                                   :turn i
                                                   :data {:tool "verify"
                                                          :category "success"}}))
                         g (range 100))]
        (is (= 40 (count (get-in many [:nodes "B1" :activity]))))
        (is (= 99 (:turn (last (get-in many [:nodes "B1" :activity]))))
            "and keeps the most recent")))
    (testing "a branch's attempts are readable off the graph, in turn order"
      (let [cs (graph/branch-claims g "B1")]
        (is (= ["c1" "c2"] (mapv :claim cs)))
        (is (= [:confirmed :refuted] (mapv :status cs)))))))

(deftest working-marks-the-live-frontier
  ;; Which nodes are being worked on right now: for each ACTIVE branch, the
  ;; tip of its chain. A branch node sits at the left end of its own chain,
  ;; so marking the branch alone never shows where a line has got to.
  (let [g (graph/fold events)]
    (testing "the tip of an active branch's chain, not the branch node"
      (is (contains? (graph/working g) "B1@3"))
      (is (not (contains? (graph/working g) "B1"))))
    (testing "an active branch with no attempts yet marks itself"
      (is (contains? (graph/working g) "B1.2")))
    (testing "closed branches are not working"
      (is (not (contains? (graph/working g) "B2"))))
    (testing "nothing is working once every branch has closed"
      (let [done (graph/apply-event g {:kind "branch-closed" :branch_id "B1"
                                       :data {:status "exhausted"}})
            done (graph/apply-event done {:kind "branch-closed" :branch_id "B1.2"
                                          :data {:status "culled"}})]
        (is (empty? (graph/working done)))))))

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
    (testing "pan shifts every projection by the drag, and picking follows"
      (let [p (graph/with-pan t [120.0 -40.0])
            [x0 y0] (graph/world->px t [2.0 1.0])
            [x1 y1] (graph/world->px p [2.0 1.0])]
        (is (< (abs (- (- x1 x0) 120.0)) 1e-9))
        (is (< (abs (- (- y1 y0) -40.0)) 1e-9))
        (is (= "b" (graph/nearest positions p [x1 y1] 30))
            "a dragged node is picked at its new position")
        (is (nil? (graph/nearest positions p [x0 y0] 10))
            "and no longer at its old one")
        (let [[wx wy] (graph/px->world p (graph/world->px p [2.0 1.0]))]
          (is (< (abs (- wx 2.0)) 1e-9))
          (is (< (abs (- wy 1.0)) 1e-9)))))
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
