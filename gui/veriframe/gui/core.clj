;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui.core
  "The optional GUI: a live scene graph of a run's solution space.

  Strictly an HTTP client of the server — this process holds no engines, no
  database handle, and no run state of its own. Everything it shows is read
  back through the same REST API any other client uses, with the journal
  cursor endpoint doing the tailing, and everything it does (interventions,
  abort, resume) goes through a POST. The server neither knows nor cares
  that a GUI exists, which is what keeps `jolt serve` headless.

  This namespace is the window shell: header bar (run picker and controls
  land here, vf-se8), the graph pane (a placeholder until the :gl-area
  lands, vf-yls), the branch log panel (vf-bku), and the intervention input
  bar (vf-bsc). The layout is real so each later issue fills a hole rather
  than reflowing the window."
  (:require [glimmer.core :as ui]
            [glimmer.ratom :as r]))

(def default-base-url "http://127.0.0.1:3999")

(defonce state
  (r/atom {:base-url default-base-url
           :run nil          ; selected run id (vf-se8)
           :selected nil     ; selected branch node (vf-bku)
           :status "not connected"
           :draft ""}))      ; intervention input text (vf-bsc)

(defn- header []
  [:hbox {:spacing 8}
   [:label {:markup "<b>veriframe</b>"}]
   [:label {:label (str "  " (:base-url @state))}]
   [:label {:label (str "  •  " (:status @state)) :xalign 0.0}]])

(defn- graph-pane []
  [:frame {:label "solution space"}
   [:label {:label (str "graph pane\n\n"
                        "branch nodes render here as the beam explores\n"
                        "(vf-yls: glimmer-gl :gl-area)")
            :wrap true}]])

(defn- log-panel []
  [:frame {:label "branch log"}
   [:scrolled
    [:label {:label (if-let [b (:selected @state)]
                      (str "log for " b)
                      "click a node to inspect its branch\n(vf-bku)")
             :wrap true :xalign 0.0}]]])

(defn- input-bar []
  [:hbox {:spacing 8}
   [:entry {:text (:draft @state)
            :placeholder "interject: a message for the selected branch, applied at its next turn boundary"
            :on-change #(swap! state assoc :draft %)
            :on-activate #(swap! state assoc :draft "")}]
   [:button {:label "send"
             ;; vf-bsc wires this to POST /v1/runs/:id/interventions.
             :on-click #(swap! state assoc :draft "")}]])

(defn root []
  [:vbox {:spacing 8}
   [header]
   [:separator]
   [:hbox {:spacing 8 :homogeneous false}
    [graph-pane]
    [log-panel]]
   [:separator]
   [input-bar]])

(defn -main [& _]
  (ui/run root :title "veriframe" :width 1100 :height 720))
