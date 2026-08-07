;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui-style-test
  "Node appearance and its polygon geometry — pure, so the parts that fail
  invisibly in a screenshot are the parts under test."
  (:require [clojure.test :refer [deftest testing is are]]
            [veriframe.gui.style :as style]))

(deftest tools-map-to-engine-families
  (are [tool fam] (= fam (style/engine tool))
    "verify"          :prolog
    "add_rule"        :prolog
    "verify_smt"      :smt
    "verify_template" :smt
    "verify_lean"     :lean
    "proof_step"      :lean
    "lean_search"     :lean
    "octave_eval"     :octave
    "verify_octave"   :octave
    "thesis"          :meta
    "audit"           :meta
    "done"            :meta
    nil               :meta))

(deftest shape-encodes-engine-ring-encodes-status
  (testing "same status, different tool: shapes differ, rings match"
    (let [a (style/node-style {:status :active :tool "verify"})
          b (style/node-style {:status :active :tool "verify_lean"})]
      (is (not= (:shape a) (:shape b)))
      (is (= (:ring a) (:ring b)))))
  (testing "same tool, different status: shapes match, rings differ"
    (let [a (style/node-style {:status :active :tool "verify_smt"})
          b (style/node-style {:status :culled :tool "verify_smt"})]
      (is (= (:shape a) (:shape b)))
      (is (not= (:ring a) (:ring b)))))
  (testing "the seed node is its own thing"
    (is (= (style/status-color :seed) (:ring (style/node-style {:status :seed})))))
  (testing "an unknown status still yields a drawable style"
    (let [s (style/node-style {:status :something-new :tool "verify"})]
      (is (every? some? [(:shape s) (:fill s) (:ring s)])))))

(deftest radius-grows-with-confirmed-work-and-is-capped
  (is (< (style/node-radius {:confirmed 0})
         (style/node-radius {:confirmed 3})
         (style/node-radius {:confirmed 8})))
  (is (= (style/node-radius {:confirmed 10})
         (style/node-radius {:confirmed 400}))))

(deftest polygon-and-vertex-geometry
  (testing "every vertex sits on the circle of the given radius"
    (doseq [shape [:circle :square :diamond :triangle :hexagon]]
      (doseq [[x y] (style/polygon 100.0 50.0 20.0 shape)]
        (is (< (abs (- 20.0 (Math/sqrt (+ (Math/pow (- x 100.0) 2)
                                          (Math/pow (- y 50.0) 2)))))
               1e-9)))))
  (testing "a triangle has 3 corners, a hexagon 6"
    (is (= 3 (count (style/polygon 0 0 1 :triangle))))
    (is (= 6 (count (style/polygon 0 0 1 :hexagon)))))
  (testing "shape-verts emits one triangle per edge, 5 floats per vertex"
    (let [v (style/shape-verts 0.0 0.0 10.0 :hexagon [1.0 0.0 0.0])]
      (is (= (* 6 3 5) (count v)))
      (is (every? number? v))))
  (testing "a selected node draws more geometry than an unselected one"
    (let [node {:status :active :tool "verify" :confirmed 2}]
      (is (> (count (style/node-verts 0.0 0.0 node true))
             (count (style/node-verts 0.0 0.0 node false)))))))
