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
  database handle, and no run state of its own. Everything it shows arrives
  through veriframe.gui.api (the journal cursor doing the tailing), the
  event stream folds through veriframe.gui.graph into the node graph the
  GL pane draws, and everything it does (interventions, abort, resume)
  goes back through a POST. The server neither knows nor cares that a GUI
  exists, which is what keeps `jolt serve` headless."
  (:require [clojure.string :as str]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [veriframe.gui.api :as api]
            [veriframe.gui.glpane :as glpane]
            [veriframe.gui.graph :as graph]))

(defn default-base-url
  "Where the server is. Defaults to the same port `jolt serve` does, so the
  GUI finds a default server with no configuration; `VERIFRAME_URL` points
  it elsewhere, and `HARNESS_PORT` alone is enough when only the port moved
  (the server reads that same variable)."
  []
  (or (System/getenv "VERIFRAME_URL")
      (str "http://127.0.0.1:" (or (System/getenv "HARNESS_PORT") "3985"))))

(defonce state
  (r/atom {:base-url (default-base-url)
           :runs []                     ; known runs, newest first
           :run nil                     ; selected run id
           :graph (graph/empty-graph)   ; folded scene graph
           :event-count 0
           :selected nil                ; selected branch node
           :branch-log nil              ; branch-detail body for :selected
           :connected? false
           :notice nil                  ; transient feedback line
           :budget ""                   ; resume budget-extension entry
           :draft ""}))                 ; intervention input text

(defonce poller (atom nil))

;; --- selection and the branch log --------------------------------------------

(defn- refresh-branch-log! []
  (let [{:keys [base-url run selected]} @state]
    (when (and run selected (not= "seed" selected))
      (future
        (let [r (api/branch-detail base-url run selected)]
          (when (and (:ok r) (= selected (:selected @state)))
            (swap! state assoc :branch-log (:body r))))))))

(defn- select-node! [id]
  (swap! state assoc :selected id :branch-log nil)
  (refresh-branch-log!)
  (glpane/request-render!))

(defn- set-hover!
  "Called only when the hovered node changes, so this repaints the window
  at pointer speed only across node boundaries, not per motion event."
  [id]
  (swap! state assoc :hover id))

;; --- run selection and the poll loop -----------------------------------------

(defn- connect-to!
  "Tail `run-id`, replacing any current tail. The cursor starts at zero so
  the fold sees the run from its first event."
  [run-id]
  (let [base (:base-url @state)]
    (when-let [{:keys [stop!]} @poller] (stop!))
    (swap! state assoc :run run-id :graph (graph/empty-graph) :event-count 0
           :selected nil :branch-log nil :notice nil)
    (glpane/request-render!)
    (when run-id
      (reset! poller
              (api/start-poller!
               {:base base :run-id run-id
                :on-events
                (fn [evs]
                  (swap! state
                         (fn [s] (-> s
                                     (update :graph #(reduce graph/apply-event % evs))
                                     (update :event-count + (count evs)))))
                  (let [sel (:selected @state)]
                    (when (some #(= sel (:branch_id %)) evs)
                      (refresh-branch-log!)))
                  (glpane/request-render!))
                :on-status
                (fn [s] (swap! state assoc :connected? (:connected? s)))})))))

(defn- refresh-runs!
  "Re-fetch the run list; keep the current selection when it still exists,
  else tail the newest run.

  Off the main thread, like every other call here: GTK's loop owns this
  thread, and a synchronous request would freeze the window for as long as
  the server took to answer — up to the socket timeout when it is down.
  glimmer marshals ratom writes made off the main thread back onto the
  loop, so the swap! is safe from here."
  []
  (future
    (let [base (:base-url @state)
          runs (vec (some-> (api/list-runs base) :body :runs))]
      (swap! state assoc :runs runs)
      (let [current (:run @state)]
        (if (and current (some #(= current (:id %)) runs))
          (swap! state assoc :run-status
                 (:status (first (filter #(= current (:id %)) runs))))
          (connect-to! (some-> runs first :id)))))))

(defn- cycle-run!
  "Step through the known runs; the picker with no dropdown widget."
  [delta]
  (let [{:keys [runs run]} @state
        n (count runs)]
    (when (pos? n)
      (let [i (or (first (keep-indexed #(when (= run (:id %2)) %1) runs)) 0)
            j (mod (+ i delta) n)]
        (connect-to! (:id (nth runs j)))))))

;; --- actions ------------------------------------------------------------------

(defn- notice! [msg] (swap! state assoc :notice msg))

(defn- send-intervention!
  "The interjection path: a message for the selected branch (or the whole
  run), applied by the server at the next turn boundary. Bound to both the
  send button and Enter in the entry (:on-activate)."
  []
  (let [{:keys [base-url run selected draft]} @state
        branch (when (and selected (not= "seed" selected)) selected)]
    (cond
      (str/blank? draft) nil
      (nil? run) (notice! "no run selected")
      :else
      ;; Clear the entry immediately so Enter feels instant; the POST
      ;; reports back when it lands.
      (do (swap! state assoc :draft "")
          (notice! (str "sending to " (or branch "the run") "…"))
          (future
            (let [r (api/intervene! base-url run
                                    {:branch-id branch :kind "message"
                                     :payload draft})]
              (notice! (if (:ok r)
                         (str "queued for " (or branch "the run")
                              " — applies at the next turn boundary")
                         (str "failed: " (:error r))))))))))

(defn- abort-run! []
  (when-let [run (:run @state)]
    (future
      (let [r (api/abort! (:base-url @state) run)]
        (notice! (if (:ok r) "abort requested"
                     (str "abort failed: " (:error r))))))))

(defn- resume-run! []
  (when-let [run (:run @state)]
    (future
      (let [budget (parse-long (str (:budget @state)))
            r (api/resume! (:base-url @state) run budget)]
        (notice! (if (:ok r)
                   (str "resuming" (when budget (str " with budget " budget)))
                   (str "resume failed: " (:error r))))))))

;; --- components ---------------------------------------------------------------

(defn- header []
  (let [{:keys [runs run connected? notice budget]} @state
        row (first (filter #(= run (:id %)) runs))]
    [:hbox {:spacing 8}
     [:label {:markup "<b>veriframe</b>"}]
     [:button {:label "⟳" :tooltip "refresh runs" :on-click refresh-runs!}]
     [:button {:label "◀" :on-click #(cycle-run! 1)}]
     [:label {:label (if run
                       (str (subs (str run) 0 8) " · " (or (:status row) "?"))
                       "no run")}]
     [:button {:label "▶" :on-click #(cycle-run! -1)}]
     [:button {:label "abort" :on-click abort-run!}]
     [:button {:label "resume" :on-click resume-run!}]
     [:entry {:text budget :placeholder "turns" :width-request 70
              :on-change #(swap! state assoc :budget %)}]
     [:label {:label (cond
                       notice (str "  " notice)
                       (not run) ""
                       connected? "  tailing"
                       :else "  disconnected — retrying")
              :xalign 0.0 :hexpand true}]]))

(defn- scene-source []
  {:graph (:graph @state) :selected (:selected @state)})

(defn- graph-pane
  "Deref-free on purpose: the :gl-area widget mounts once and repaints via
  request-render!, not via reconciliation."
  []
  [:frame {:label "solution space" :hexpand true :vexpand true}
   [glpane/pane scene-source select-node! set-hover!]])

(defn- legend []
  (let [{:keys [selected graph event-count]} @state]
    [:hbox {:spacing 12}
     [:label {:markup (str "<b>shape</b> = engine:  ■ prolog   ◆ smt"
                           "   ▲ lean   ⬢ octave   ● harness")}]
     [:label {:markup "<b>ring</b> = status"}]
     [:button {:label "recenter" :on-click glpane/reset-pan!}]
     [:label {:label (str (count (:nodes graph)) " nodes · "
                          event-count " events"
                          (when selected (str " · selected " selected)))
              :xalign 1.0 :hexpand true}]]))

(defn- status-line
  "What the pointer is over. Also the fastest read on whether the pane is
  receiving input at all."
  []
  (let [{:keys [selected graph hover]} @state
        node (get-in graph [:nodes (or hover selected)])]
    [:label {:label (cond
                      hover (str "▸ " hover
                                 (when-let [t (:tool node)] (str " · " t))
                                 " — click to inspect, drag to pan")
                      selected (str "selected " selected " — click empty space to clear")
                      :else "hover a node to identify it; drag to pan")
             :xalign 0.0}]))

(defn- branch-text [node detail]
  (str "status: " (name (or (:status node) :unknown))
       (when-let [reason (:reason node)] (str "\n" reason))
       "\nconfirmed artifacts: " (or (:confirmed node) 0)
       (when-let [c (:critic node)]
         (str "\ncritic: progress " (:progress c) " · momentum " (:momentum c)
              " · distinctness " (:distinctness c) " · viability " (:viability c)
              (when (:spared? node) "\nspared by Pareto retention")))
       (when-let [t (:thesis node)] (str "\n\nthesis: " t))
       (when-let [turns (seq (:turns detail))]
         (str "\n\n— turns —\n"
              (->> (take-last 14 turns)
                   (map #(str "T" (:turn %) " " (:tool_name %)
                              " [" (:category %) "]"))
                   (str/join "\n"))))
       (when-let [arts (seq (filter #(= "confirmed" (:claim_status %))
                                    (:artifacts detail)))]
         (str "\n\n— confirmed —\n"
              (->> (take-last 6 arts)
                   (map #(let [c (str (:claim %))]
                           (str "✓ [" (:kind %) "] "
                                (subs c 0 (min 110 (count c)))
                                (when (> (count c) 110) "…"))))
                   (str/join "\n"))))))

(defn- log-panel []
  (let [{:keys [graph selected branch-log]} @state
        node (get-in graph [:nodes selected])]
    [:frame {:label (if selected (str "branch " selected) "branch log")
             :vexpand true :width-request 400}
     [:scrolled {:vexpand true}
      [:label {:text (cond
                       (nil? selected)
                       "click a node to inspect its branch"
                       (= "seed" selected)
                       (str "inherited artifacts\n" (:label node))
                       :else (branch-text node branch-log))
               :wrap true :xalign 0.0 :margin 8}]]]))

(defn- input-bar []
  [:hbox {:spacing 8}
   [:entry {:text (:draft @state)
            :hexpand true
            :placeholder "interject: a message for the selected branch, applied at its next turn boundary"
            :on-change #(swap! state assoc :draft %)
            :on-activate send-intervention!}]
   [:button {:label "send" :on-click send-intervention!}]])

(defn root []
  [:vbox {:spacing 8 :margin 8}
   [header]
   [legend]
   [:separator]
   [:hbox {:spacing 8 :vexpand true}
    [graph-pane]
    [log-panel]]
   [status-line]
   [:separator]
   [input-bar]])

(defn -main [& _]
  (refresh-runs!)
  (ui/run root :title "veriframe" :width 1100 :height 720))
