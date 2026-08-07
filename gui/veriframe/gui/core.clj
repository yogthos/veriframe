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
            [glimmer.ratom :as r]
            [veriframe.gui.api :as api]))

(def default-base-url "http://127.0.0.1:3999")

(defonce state
  (r/atom {:base-url default-base-url
           :run nil          ; selected run id (vf-se8 adds the picker)
           :selected nil     ; selected branch node (vf-bku)
           :connected? false
           :events []        ; raw journal events; vf-cht folds these
           :draft ""}))      ; intervention input text (vf-bsc)

(defonce poller (atom nil))

(defn- connect!
  "Pick the newest run and tail it. vf-se8 replaces the auto-pick with a
  run picker; the poll wiring stays exactly this."
  []
  (let [base (:base-url @state)
        runs (api/list-runs base)
        run-id (some-> runs :body :runs first :id)]
    (when-let [{:keys [stop!]} @poller] (stop!))
    (swap! state assoc :run run-id :events [])
    (when run-id
      (reset! poller
              (api/start-poller!
               {:base base :run-id run-id
                :on-events (fn [evs] (swap! state update :events into evs))
                :on-status (fn [s] (swap! state assoc
                                          :connected? (:connected? s)))})))))

(defn- header []
  [:hbox {:spacing 8}
   [:label {:markup "<b>veriframe</b>"}]
   [:label {:label (str "  " (:base-url @state))}]
   [:label {:label (str "  •  "
                        (cond
                          (not (:run @state)) "no run found"
                          (:connected? @state) (str "tailing " (subs (str (:run @state)) 0 8))
                          :else "disconnected — retrying"))
            :xalign 0.0}]])

(defn- graph-pane []
  (let [evs (:events @state)]
    [:frame {:label "solution space" :hexpand true :vexpand true}
     [:label {:label (str "graph pane (vf-yls renders this)\n\n"
                          (count evs) " journal events\n"
                          (when-let [e (peek evs)]
                            (str "latest: [" (or (:branch_id e) "run") "] "
                                 (:kind e))))
              :wrap true}]]))

(defn- log-panel []
  [:frame {:label "branch log" :vexpand true :width-request 360}
   [:scrolled {:vexpand true}
    [:label {:label (if-let [b (:selected @state)]
                      (str "log for " b)
                      "click a node to inspect its branch\n(vf-bku)")
             :wrap true :xalign 0.0 :margin 8}]]])

(defn- input-bar []
  [:hbox {:spacing 8}
   [:entry {:text (:draft @state)
            :hexpand true
            :placeholder "interject: a message for the selected branch, applied at its next turn boundary"
            :on-change #(swap! state assoc :draft %)
            :on-activate #(swap! state assoc :draft "")}]
   [:button {:label "send"
             ;; vf-bsc wires this to POST /v1/runs/:id/interventions.
             :on-click #(swap! state assoc :draft "")}]])

(defn root []
  [:vbox {:spacing 8 :margin 8}
   [header]
   [:separator]
   [:hbox {:spacing 8 :vexpand true}
    [graph-pane]
    [log-panel]]
   [:separator]
   [input-bar]])

(defn -main [& _]
  (connect!)
  (ui/run root :title "veriframe" :width 1100 :height 720))
