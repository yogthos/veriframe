;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui.input
  "The pointer state machine for the graph pane: press, motion, release.

  Pure, and separate from the GL pane, because this is exactly the logic
  that fails silently behind a toolkit — a handler that throws inside a
  GTK callback takes the click with it and leaves no trace. Here it is a
  value in, value out, and the suite covers it without a display.

  The rule it encodes: a press starts a *candidate* drag, motion past a
  few pixels of slop commits it, and release picks only if the pointer
  never left that slop. Panning the graph therefore never changes the
  selection by accident, and a plain click always selects.")

(def drag-slop-px 5.0)

(defn press
  "Begin a candidate drag at [x y]."
  [state x y]
  (assoc state :drag {:from [x y] :pan0 (or (:pan state) [0.0 0.0]) :moved? false}))

(defn motion
  "Pointer moved. While a drag is live this pans; otherwise it only records
  the pointer for hover. Returns the new state."
  [state x y]
  (let [state (assoc state :pointer [x y])]
    (if-let [{:keys [from pan0 moved?]} (:drag state)]
      (let [[fx fy] from
            [px py] pan0
            dx (- x fx)
            dy (- y fy)]
        (assoc state
               :pan [(+ px dx) (+ py dy)]
               :drag (assoc (:drag state)
                            :moved? (or moved?
                                        (> (Math/sqrt (+ (* dx dx) (* dy dy)))
                                           drag-slop-px)))))
      state)))

(defn release
  "Pointer released. Returns [state' pick?] — `pick?` is true when this
  gesture was a click rather than a drag, and the caller should run a hit
  test. A release with no matching press still counts as a click, so a
  missed press event cannot make the pane feel dead."
  [state _x _y]
  (let [moved? (get-in state [:drag :moved?])]
    [(dissoc state :drag) (not moved?)]))
