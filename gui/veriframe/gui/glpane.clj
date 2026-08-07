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
            [veriframe.gui.graph :as graph]))

(def ^:private vs-src
  (str "#version 150\n"
       "in vec2 pos; in vec3 col; out vec3 vcol; uniform vec2 vp;\n"
       "void main() { vcol = col;\n"
       "  gl_Position = vec4(2.0*pos.x/vp.x - 1.0, 1.0 - 2.0*pos.y/vp.y, 0.0, 1.0); }"))

(def ^:private fs-src
  (str "#version 150\n"
       "in vec3 vcol; out vec4 frag;\n"
       "void main() { frag = vec4(vcol, 1.0); }"))

(defonce ^:private st (atom {:w 1 :h 1}))

(def status-color
  {:active    [0.28 0.72 0.38]
   :done      [0.25 0.55 0.95]
   :culled    [0.85 0.35 0.30]
   :exhausted [0.90 0.65 0.25]
   :abandoned [0.55 0.55 0.55]
   :seed      [0.65 0.45 0.85]})

(def ^:private edge-color [0.45 0.45 0.50])
(def ^:private select-color [0.97 0.97 0.97])
(def pick-radius 24.0)

(defn node-radius [node]
  (+ 9.0 (* 2.0 (min 8 (or (:confirmed node) 0)))))

;; --- geometry (pure) ---------------------------------------------------------

(defn- quad-verts [cx cy r [cr cg cb]]
  (let [x0 (- cx r) x1 (+ cx r) y0 (- cy r) y1 (+ cy r)]
    [x0 y0 cr cg cb, x1 y0 cr cg cb, x1 y1 cr cg cb
     x0 y0 cr cg cb, x1 y1 cr cg cb, x0 y1 cr cg cb]))

(defn- line-verts [[x0 y0] [x1 y1] [cr cg cb]]
  [x0 y0 cr cg cb, x1 y1 cr cg cb])

(defn scene-verts
  "{:lines [floats] :tris [floats]} for the whole scene, in pixel space."
  [g positions t selected]
  (let [px (fn [id] (graph/world->px t (positions id)))]
    {:lines (vec (mapcat (fn [[from to]]
                           (when (and (positions from) (positions to))
                             (line-verts (px from) (px to) edge-color)))
                         (graph/edges g)))
     :tris (vec (mapcat (fn [id]
                          (let [node (get-in g [:nodes id])
                                [cx cy] (px id)
                                r (node-radius node)
                                color (status-color (:status node)
                                                    [0.7 0.7 0.7])]
                            (concat
                             (when (= id selected)
                               (quad-verts cx cy (+ r 4.0) select-color))
                             (quad-verts cx cy r color))))
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
  output at the pane's present size. Shared by render and pick, which is
  the whole point."
  [{:keys [graph selected]}]
  (let [{:keys [w h]} @st
        positions (graph/layout graph)]
    (when (seq positions)
      {:g graph :positions positions :selected selected
       :t (graph/fit positions w h 60)})))

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

(defn pane
  "The :gl-area hiccup. `source` is a zero-arg fn returning
  {:graph <folded> :selected <id|nil>}; `on-pick` receives the picked node
  id (or nil for a background click) on every press."
  [source on-pick]
  [:gl-area {:version [3 2] :depth-buffer false :hexpand true :vexpand true
             :on-realize realize!
             :on-render (fn [_area] (render! source))
             :on-resize (fn [_area w h]
                          (swap! st assoc :w (max 1 w) :h (max 1 h))
                          (gl/gl-viewport 0 0 w h))
             :on-button (fn [_area _btn pressed? x y]
                          (when pressed?
                            (on-pick (pick source x y))))}])
