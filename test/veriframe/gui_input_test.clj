;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui-input-test
  "The pane's pointer state machine. Behind a toolkit these failures are
  silent — a handler that throws inside a GTK callback swallows the click
  — so the logic lives here where it can be exercised without a display."
  (:require [clojure.test :refer [deftest testing is]]
            [veriframe.gui.input :as input]))

(def ^:private st0 {:pan [0.0 0.0]})

(deftest a-plain-click-picks
  (let [s (input/press st0 100.0 100.0)
        [s' pick?] (input/release s 100.0 100.0)]
    (is pick? "press and release in place is a click")
    (is (= [0.0 0.0] (:pan s')) "and pans nothing")
    (is (nil? (:drag s')) "the gesture is over")))

(deftest a-tiny-wobble-is-still-a-click
  ;; A mouse moves a pixel or two under a real finger. Treating that as a
  ;; drag is what makes a pane feel unclickable.
  (let [s (-> (input/press st0 100.0 100.0)
              (input/motion 102.0 101.0))
        [_ pick?] (input/release s 102.0 101.0)]
    (is pick?)))

(deftest a-real-drag-pans-and-does-not-pick
  (let [s (-> (input/press st0 100.0 100.0)
              (input/motion 160.0 130.0))
        [s' pick?] (input/release s 160.0 130.0)]
    (is (not pick?) "a drag must not change the selection")
    (is (= [60.0 30.0] (:pan s')) "the pan is the drag delta")))

(deftest dragging-accumulates-across-gestures
  (let [after-one (first (input/release
                          (-> (input/press st0 0.0 0.0)
                              (input/motion 50.0 0.0))
                          50.0 0.0))
        after-two (first (input/release
                          (-> (input/press after-one 0.0 0.0)
                              (input/motion 30.0 20.0))
                          30.0 20.0))]
    (is (= [80.0 20.0] (:pan after-two))
        "a second drag starts from where the first left off")))

(deftest motion-without-a-press-does-not-pan
  (let [s (input/motion st0 400.0 400.0)]
    (is (= [0.0 0.0] (:pan s)))
    (is (= [400.0 400.0] (:pointer s)) "but it is tracked, for hover")))

(deftest a-release-with-no-press-still-clicks
  ;; Defensive: if a press event is ever missed, the pane must not become
  ;; permanently unclickable.
  (let [[_ pick?] (input/release st0 10.0 10.0)]
    (is pick?)))
