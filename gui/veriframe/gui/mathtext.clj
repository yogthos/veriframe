;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui.mathtext
  "Turn the model's plain-text mathematics into Pango markup.

  The claims are prose, not LaTeX — `uE({3,5,9,15}) = 17/45`, `3^a 5^b`,
  `sum 1/m >= 1` — so this is a conservative beautifier, not a typesetter.
  Pango gives sub/superscripts and Unicode; full LaTeX would need webkitgtk
  and MathJax, which is far more machinery than these strings deserve.

  Two rules make it safe.

  ESCAPE FIRST, ALWAYS. Pango markup is XML, so an unescaped `<` or `&`
  makes the whole label fail to parse — and a panel that shows SMT-LIB is
  full of `(<= x 2)` and `&`. Every path here escapes before it inserts a
  single tag.

  PROSE ONLY. `plain` is for code: escaped and otherwise untouched, because
  rewriting `<=` to `≤` inside an encoding would misrepresent what actually
  ran. `math` is for claims and prose, where the rewrite aids reading."
  (:require [clojure.string :as str]))

(defn escape
  "XML-escape for Pango. Ampersand first, or it double-escapes the entities
  the later replacements introduce."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn plain
  "Code and other verbatim text: escaped, never rewritten."
  [s]
  (escape s))

(def ^:private operators
  ;; Applied after escaping, so the arrows match the ESCAPED forms.
  [["&lt;=" "≤"]
   ["&gt;=" "≥"]
   ["!=" "≠"]
   ["&lt;-&gt;" "↔"]
   ["=&gt;" "⇒"]])

(def ^:private words
  ;; Whole words only: "sum" inside "consumed" must not become ∑.
  [[#"\bsum\b" "∑"]
   [#"\bprod\b" "∏"]
   [#"\blcm\b" "lcm"]
   [#"\bin Z\b" "∈ ℤ"]
   [#"\bforall\b" "∀"]
   [#"\bexists\b" "∃"]])

(defn math
  "Prose containing mathematics, as Pango markup.

  Rewrites, in order: escape; comparison operators; `x^n` to a superscript
  and `x_n` to a subscript (single token only, so `u_E` and `3^a` work while
  a stray underscore in prose is left alone); a few whole words."
  [s]
  (let [t (escape s)
        t (reduce (fn [acc [from to]] (str/replace acc from to)) t operators)
        ;; Superscript: 3^a, 5^{ab}, x^2 — one token or a braced group.
        t (str/replace t #"\^\{([^}]{1,12})\}" "<sup>$1</sup>")
        t (str/replace t #"\^([A-Za-z0-9]{1,4})" "<sup>$1</sup>")
        ;; Subscript: u_E, a_3, m_{ij}
        t (str/replace t #"_\{([^}]{1,12})\}" "<sub>$1</sub>")
        t (str/replace t #"_([A-Za-z0-9]{1,4})" "<sub>$1</sub>")
        ;; uE(...) and uC(...) are this campaign's own notation.
        t (str/replace t #"\bu([EC])\b" "u<sub>$1</sub>")
        t (reduce (fn [acc [re to]] (str/replace acc re to)) t words)]
    t))

(defn heading
  "A section heading: escaped, bold, and slightly dimmed."
  [s]
  (str "<b>" (escape s) "</b>"))

(defn dim
  "Secondary text — turn numbers, tool names, statuses."
  [s]
  (str "<span foreground=\"#8a8f98\">" (escape s) "</span>"))

(defn mono
  "Code, in a monospace face, escaped and never rewritten."
  [s]
  (str "<tt>" (plain s) "</tt>"))
