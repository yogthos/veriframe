;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui.newrun
  "The new-run form, as a value.

  Everything the form collects is a string, because that is what GTK hands
  back — including the two numbers. Turning those into a request is the part
  worth testing, so it lives here rather than inside a click handler.

  A knob left empty means 'whatever the server's config says', and is left
  out of the body entirely. A knob filled in WRONG is refused instead of
  dropped: silently ignoring `max turns: lots` would start a real run on the
  default budget while the user believed they had asked for something else."
  (:require [clojure.string :as str]))

(defn- blank? [v] (str/blank? (str v)))

(defn- positive-long
  "nil when absent, a long when valid, :invalid when it is neither."
  [v]
  (cond
    (blank? v) nil
    (integer? v) (if (pos? v) (long v) :invalid)
    :else (let [n (parse-long (str/trim (str v)))]
            (if (and n (pos? n)) n :invalid))))

(defn request
  "Build {:body {...}} for POST /v1/runs, or {:error \"...\"} for a form the
  server should not be asked about."
  [{:keys [problem max-turns beam-width seed-run]}]
  (let [turns (positive-long max-turns)
        width (positive-long beam-width)]
    (cond
      (blank? problem) {:error "a problem statement is required"}
      (= :invalid turns) {:error "max turns must be a positive whole number"}
      (= :invalid width) {:error "beam width must be a positive whole number"}
      :else
      {:body (cond-> {:problem (str problem)}
               turns (assoc :max_turns turns)
               width (assoc :beam_width width)
               (not (blank? seed-run)) (assoc :seed_run (str/trim (str seed-run))))})))

(defn summary
  "One line describing a built body, for the confirmation the GUI shows."
  [body]
  (let [parts (cond-> []
                (:max_turns body) (conj (str (:max_turns body) " turns"))
                (:beam_width body) (conj (str "beam " (:beam_width body)))
                (:seed_run body) (conj (str "seeded from "
                                            (subs (str (:seed_run body))
                                                  0 (min 8 (count (str (:seed_run body))))))))]
    (if (seq parts) (str/join " · " parts) "server defaults")))
