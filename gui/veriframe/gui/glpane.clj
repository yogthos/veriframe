;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui.glpane
  "The solution-space pane: the scene graph drawn in a :gl-area.

  A deliberately 2D pipeline — one program, one VBO of interleaved
  [x y r g b], pixel coordinates mapped to NDC in the vertex shader — so the
  transform that draws a node is the same pure `graph/fit` affine that
  answers a click, and picking is exact instead of an unprojection. Edges
  draw as GL_LINES, nodes as quads sized by confirmed-artifact count and
  colored by status; the selected node gets a white backing quad. Geometry
  is rebuilt and re-uploaded per render, which at beam scale (tens of
  nodes) costs nothing.

  All GL state lives here keyed by nothing — one pane per process. The
  pane re-renders when `request-render!` is called (the poll loop and the
  picker call it), coalesced by GTK to one render per frame-clock tick."
  (:require [glimmer-gl.gl :as gl]
            [glimmer-gl.gtk :as glx]
            [jolt.ffi :as ffi]
            [veriframe.gui.graph :as graph]
            [veriframe.gui.style :as style]))

(def ^:private vs-src
  (str "#version 150\n"
       "in vec2 pos; in vec3 col; out vec3 vcol; uniform vec2 vp;\n"
       "void main() { vcol = col;\n"
       "  gl_Position = vec4(2.0*pos.x/vp.x - 1.0, 1.0 - 2.0*pos.y/vp.y, 0.0, 1.0); }"))

(def ^:private fs-src
  (str "#version 150\n"
       "in vec3 vcol; out vec4 frag;\n"
       "void main() { frag = vec4(vcol, 1.0); }"))

(defonce ^:private st (atom {:w 1 :h 1 :pan [0.0 0.0]}))

(def pick-radius 26.0)
(def ^:private drag-slop-px 5.0)

;; --- geometry (pure) ---------------------------------------------------------

(defn- line-verts [[x0 y0] [x1 y1] [cr cg cb]]
  [x0 y0 cr cg cb, x1 y1 cr cg cb])

(defn scene-verts
  "{:lines [floats] :tris [floats]} for the whole scene, in pixel space.
  Nodes draw last so an edge never crosses a node body."
  [g positions t selected]
  (let [px (fn [id] (graph/world->px t (positions id)))]
    {:lines (vec (mapcat (fn [[from to]]
                           (when (and (positions from) (positions to))
                             (line-verts (px from) (px to) style/edge-color)))
                         (graph/edges g)))
     :tris (vec (mapcat (fn [id]
                          (let [[cx cy] (px id)]
                            (style/node-verts cx cy (get-in g [:nodes id])
                                              (= id selected))))
                        (keys positions)))}))

;; --- GL lifecycle ------------------------------------------------------------

(defn- realize! [area]
  (glx/make-current area)
  (when-let [prog (gl/make-program vs-src fs-src)]
    (let [vao (gl/gen-one gl/gl-gen-vertex-arrays)
          vbo (gl/gen-one gl/gl-gen-buffers)
          _ (gl/gl-use-program prog)
          pos (gl/gl-get-attrib-location prog "pos")
          col (gl/gl-get-attrib-location prog "col")
          vp (gl/gl-get-uniform-location prog "vp")
          stride (* 5 (ffi/sizeof :float))]
      (gl/gl-bind-vertex-array vao)
      (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
      (gl/gl-enable-vertex-attrib-array pos)
      (gl/gl-vertex-attrib-pointer pos 2 gl/GL-FLOAT gl/GL-FALSE stride 0)
      (gl/gl-enable-vertex-attrib-array col)
      (gl/gl-vertex-attrib-pointer col 3 gl/GL-FLOAT gl/GL-FALSE stride
                                   (* 2 (ffi/sizeof :float)))
      (swap! st merge {:area area :prog prog :vao vao :vbo vbo :vp-loc vp}))))

(defn- draw! [mode verts first-vert]
  (when (seq verts)
    (gl/gl-draw-arrays mode first-vert (quot (count verts) 5))))

(defn- upload-and-draw! [{:keys [lines tris]}]
  (let [{:keys [prog vao vbo vp-loc w h]} @st
        all (into (vec lines) tris)
        ptr (gl/write-floats all)]
    (gl/gl-use-program prog)
    (gl/gl-bind-vertex-array vao)
    (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
    (gl/gl-buffer-data gl/GL-ARRAY-BUFFER (* (count all) (ffi/sizeof :float))
                       ptr gl/GL-DYNAMIC-DRAW)
    (ffi/free ptr)
    (gl/gl-uniform-2f vp-loc (double w) (double h))
    (draw! gl/GL-LINES lines 0)
    (draw! gl/GL-TRIANGLES tris (quot (count lines) 5))))

(defn- view
  "The current graph, positions, and world->px transform for `source`'s
  output at the pane's present size, including the drag offset. Shared by
  render and pick, which is the whole point: what you see is what you hit."
  [{:keys [graph selected]}]
  (let [{:keys [w h pan]} @st
        positions (graph/layout graph)]
    (when (seq positions)
      {:g graph :positions positions :selected selected
       :t (graph/with-pan (graph/fit positions w h 70) pan)})))

(defn- render! [source]
  (gl/gl-clear-color 0.11 0.11 0.13 1.0)
  (gl/gl-clear gl/GL-COLOR-BUFFER-BIT)
  (when-let [{:keys [g positions t selected]} (view (source))]
    (upload-and-draw! (scene-verts g positions t selected))))

(defn pick
  "The node id under pixel [x y], or nil."
  [source x y]
  (when-let [{:keys [positions t]} (view (source))]
    (graph/nearest positions t [x y] pick-radius)))

(defn request-render! []
  (when-let [area (:area @st)]
    (glx/queue-render area)))

(defn reset-pan! []
  (swap! st assoc :pan [0.0 0.0])
  (request-render!))

;; --- drag to pan --------------------------------------------------------------
;; Press starts a candidate drag; motion past a few pixels of slop turns it
;; into a real one and stops it from also being a click. Release picks only
;; when the pointer never left that slop, so dragging the graph around never
;; changes the selection by accident.

(defn- press! [x y]
  (swap! st assoc :drag {:from [x y] :pan0 (:pan @st) :moved? false}))

(defn- motion! [x y]
  (when-let [{:keys [from pan0]} (:drag @st)]
    (let [[fx fy] from
          [px py] pan0
          dx (- x fx)
          dy (- y fy)]
      (swap! st (fn [s]
                  (-> s
                      (assoc :pan [(+ px dx) (+ py dy)])
                      (assoc-in [:drag :moved?]
                                (or (get-in s [:drag :moved?])
                                    (> (Math/sqrt (+ (* dx dx) (* dy dy)))
                                       drag-slop-px))))))
      (request-render!))))

(defn- release! [source on-pick x y]
  (let [moved? (get-in @st [:drag :moved?])]
    (swap! st dissoc :drag)
    (when-not moved?
      (on-pick (pick source x y)))))

(defn pane
  "The :gl-area hiccup. `source` is a zero-arg fn returning
  {:graph <folded> :selected <id|nil>}; `on-pick` receives the picked node
  id (or nil when the click landed on empty space)."
  [source on-pick]
  [:gl-area {:version [3 2] :depth-buffer false :hexpand true :vexpand true
             :on-realize realize!
             :on-render (fn [_area] (render! source))
             :on-resize (fn [_area w h]
                          (swap! st assoc :w (max 1 w) :h (max 1 h))
                          (gl/gl-viewport 0 0 w h))
             :on-motion (fn [_area x y] (motion! x y))
             :on-button (fn [_area _btn pressed? x y]
                          (if pressed?
                            (press! x y)
                            (release! source on-pick x y)))}])
