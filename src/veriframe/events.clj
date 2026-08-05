;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or
;; modify it under the terms of the GNU General Public License as
;; published by the Free Software Foundation, either version 3 of
;; the License, or (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public
;; License along with this program. If not, see
;; <https://www.gnu.org/licenses/>.

(ns veriframe.events
  "The live event bus.

  Every journal append publishes here. The durable copy is the `events` table;
  this exists so a client can watch a run without polling, and so nothing in
  the loop has to know whether anyone is watching.

  A subscriber that stops reading must never stall the loop, so taps use a
  sliding buffer: a slow watcher loses events rather than applying
  backpressure. That is the right trade because the durable journal is the
  source of truth and a client that fell behind re-reads it by cursor."
  (:require [clojure.core.async :as async]))

(def buffer-size 256)

(defonce ^:private hub
  (let [ch (async/chan (async/sliding-buffer buffer-size))]
    {:ch ch :mult (async/mult ch)}))

(defn publish!
  "Non-blocking. Returns immediately whether or not anyone is listening."
  [event]
  (async/put! (:ch hub) event)
  nil)

(defn subscribe
  "A channel receiving every event published from now on. Close it with
  `unsubscribe!` when done, or it keeps consuming a tap slot."
  ([] (subscribe buffer-size))
  ([n]
   (let [ch (async/chan (async/sliding-buffer n))]
     (async/tap (:mult hub) ch)
     ch)))

(defn unsubscribe! [ch]
  (async/untap (:mult hub) ch)
  (async/close! ch)
  nil)

(defn collect
  "Drain whatever is currently buffered on `ch`. For tests and for a polling
  client that would rather not block."
  [ch]
  (loop [acc []]
    (if-let [v (async/poll! ch)]
      (recur (conj acc v))
      acc)))
