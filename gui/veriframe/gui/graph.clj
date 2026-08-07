;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui.graph
  "Journal events folded into the scene graph the GUI renders.

  Pure and toolkit-free: {:nodes {id {...}} :order [ids] :run {...}} out of
  the event stream, incrementally — `apply-event` is the per-event step the
  poll loop feeds, `fold` the from-scratch rebuild a run switch uses. The
  fold is deliberately defensive: an event for a branch it has not seen
  opens the node rather than throwing, and unknown kinds are ignored, so a
  server that grows new event kinds never breaks an older GUI."
  (:require [clojure.string :as str]))

(defn empty-graph []
  {:nodes {} :order [] :run {}})

(defn- ensure-node [g id]
  (if (get-in g [:nodes id])
    g
    (-> g
        (update :nodes assoc id {:id id :kind :branch :status :active :confirmed 0})
        (update :order conj id))))

(defn- add-artifact
  "An artifact becomes a node chained onto the branch's previous artifact,
  so the run's reasoning grows left to right instead of the beam's flat
  topology staying flat. The beam almost never forks — seven fork
  invitations went out in one run and every one was declined — so without
  this the graph shows three branches and never changes shape again."
  [g branch-id turn data]
  (let [g (ensure-node g branch-id)
        id (str branch-id "@" turn)
        prev (get-in g [:nodes branch-id :last-artifact])
        status (keyword (or (:claim-status data) "confirmed"))]
    (-> g
        (update :nodes assoc id
                {:id id :kind :artifact :branch branch-id
                 :parent (or prev branch-id)
                 :engine (keyword (:kind data))
                 :status status
                 :claim (:claim data)
                 :turn turn})
        (update :order conj id)
        (assoc-in [:nodes branch-id :last-artifact] id))))

(defn- upd
  "Merge `m` into branch `id`'s node, creating it if the fold never saw its
  branch-opened (resumed tails start mid-stream)."
  [g id m]
  (let [g (ensure-node g id)]
    (update-in g [:nodes id] merge m)))

(defn apply-event
  "One journal event into the graph. Unknown kinds are a no-op."
  [g {:keys [kind branch_id turn data]}]
  (case kind
    "run-started"  (update g :run merge {:problem (:problem data)})
    "run-finished" (update g :run merge {:status (:status data)})
    "run-seeded"   (-> g
                       (update :run merge {:seeded data})
                       (update :nodes assoc "seed"
                               {:id "seed" :status :seed
                                :label (str (:artifacts data) " inherited artifacts")}))
    "branch-opened"   (upd g branch_id {:parent (:parent data)})
    "branch-closed"   (upd g branch_id {:status (keyword (:status data))
                                        :reason (:reason data)})
    "branch-reopened" (upd g branch_id {:status :active :reason nil})
    "thesis"          (upd g branch_id {:thesis (:goal data)})
    ;; The tool is what the node's SHAPE encodes: which engine this branch is
    ;; working in right now, as distinct from its status.
    "turn"            (upd g branch_id {:turn turn :tool (:tool data)
                                        :category (:category data)})
    "artifact"        (let [g (add-artifact g branch_id turn data)]
                        (if (= "confirmed" (some-> (:claim-status data) name))
                          (update-in g [:nodes branch_id :confirmed] (fnil inc 0))
                          g))
    "critic-score"    (upd g branch_id {:critic data})
    "cull-spared"     (upd g branch_id {:spared? true})
    "fork-invite"     (upd g branch_id {:invited turn})
    g))

(defn fold [events]
  (reduce apply-event (empty-graph) events))

(defn edges
  "Parent->child links, plus seed provenance into every root when the run
  was seeded: inherited lemmas flow into the whole beam, not one branch."
  [{:keys [nodes]}]
  (let [seed? (contains? nodes "seed")]
    (concat
     (for [{:keys [id parent]} (vals nodes)
           :when parent]
       [parent id])
     (when seed?
       (for [{:keys [id parent status kind]} (vals nodes)
             :when (and (nil? parent) (not= :seed status)
                        (not= :artifact kind))]
         ["seed" id])))))

;; --- layout and the view transform -------------------------------------------
;; Pure math, kept out of the GL pane so the headless suite covers it: node
;; placement, world<->pixel mapping, and click picking are exactly the parts
;; whose bugs are invisible in a screenshot and obvious in a test.

(defn layout
  "id -> [x y] in world units: generations in columns left to right (the
  seed, when present, is its own column 0), siblings stacked and centered
  within their column in insertion order."
  [{:keys [nodes order]}]
  (let [seed? (contains? nodes "seed")
        depth (fn depth [id]
                (cond
                  (= id "seed") 0
                  (get-in nodes [id :parent]) (inc (depth (get-in nodes [id :parent])))
                  :else (if seed? 1 0)))
        ids (concat (when seed? ["seed"]) order)
        by-col (group-by depth ids)]
    (into {}
          (for [[col members] by-col
                :let [n (count members)]
                [i id] (map-indexed vector members)]
            [id [(double col)
                 (double (- i (/ (dec n) 2.0)))]]))))

(defn fit
  "The world->pixel affine {:s :ox :oy} that places every position inside a
  `w`x`h` viewport with `pad` pixels of margin, preserving aspect. A
  single-node (degenerate) bounding box maps to the viewport center."
  [positions w h pad]
  (let [xs (map first (vals positions))
        ys (map second (vals positions))
        [x0 x1] [(reduce min xs) (reduce max xs)]
        [y0 y1] [(reduce min ys) (reduce max ys)]
        dx (max 1e-9 (- x1 x0))
        dy (max 1e-9 (- y1 y0))
        s (min (/ (- w (* 2 pad)) dx)
               (/ (- h (* 2 pad)) dy))
        ;; center the graph in the viewport
        cx (/ (+ x0 x1) 2.0)
        cy (/ (+ y0 y1) 2.0)]
    {:s s :ox (- (/ w 2.0) (* s cx)) :oy (- (/ h 2.0) (* s cy))}))

(defn with-pan
  "Shift a transform by a pixel offset — the drag. Applied after `fit`, so
  auto-fit keeps the graph framed and the pan moves the frame."
  [t [dx dy]]
  (-> t
      (update :ox + (or dx 0.0))
      (update :oy + (or dy 0.0))))

(defn world->px [{:keys [s ox oy]} [x y]]
  [(+ ox (* s x)) (+ oy (* s y))])

(defn px->world [{:keys [s ox oy]} [px py]]
  [(/ (- px ox) s) (/ (- py oy) s)])

(defn nearest
  "The node whose projected position is closest to pixel [px py], when it is
  within `max-d` pixels; nil otherwise. Picking is exact because the same
  transform that drew the node answers the click."
  [positions t [px py] max-d]
  (let [scored (for [[id p] positions
                     :let [[nx ny] (world->px t p)
                           d (Math/sqrt (+ (Math/pow (- nx px) 2)
                                           (Math/pow (- ny py) 2)))]]
                 [d id])
        [d id] (first (sort-by first scored))]
    (when (and d (<= d max-d)) id)))
