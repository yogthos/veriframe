;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui-mathtext-test
  "Pango markup for the inspector. The escaping is the part that matters:
  the panel renders SMT-LIB, which is full of `(<= x 2)`, and a single
  unescaped angle bracket makes the whole label fail to parse."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [veriframe.gui.mathtext :as mt]))

(deftest escaping-comes-first-and-covers-code
  (testing "the three XML-significant characters"
    (is (= "&amp; &lt; &gt;" (mt/escape "& < >"))))
  (testing "ampersand is escaped before the entities are introduced"
    (is (= "&amp;lt;" (mt/escape "&lt;")) "no double-escaping of a literal"))
  (testing "SMT-LIB survives verbatim through the code path"
    (let [smt "(assert (<= x 2)) (and a b) & more"
          out (mt/plain smt)]
      (is (not (str/includes? out "<=")) "raw angle brackets are gone")
      (is (str/includes? out "&lt;= x 2"))
      (is (str/includes? out "&amp; more"))))
  (testing "code is never rewritten into prettier operators"
    (is (not (str/includes? (mt/plain "(<= a b)") "≤"))
        "an encoding must read as what actually ran")
    (is (str/includes? (mt/mono "(<= a b)") "<tt>"))))

(deftest prose-gets-readable-mathematics
  (testing "comparison operators"
    (is (str/includes? (mt/math "sum 1/m >= 1") "≥"))
    (is (str/includes? (mt/math "x <= y") "≤"))
    (is (str/includes? (mt/math "a != b") "≠")))
  (testing "superscripts and subscripts"
    (is (str/includes? (mt/math "3^a 5^b") "3<sup>a</sup>"))
    (is (str/includes? (mt/math "x^{ij}") "<sup>ij</sup>"))
    (is (str/includes? (mt/math "a_3") "a<sub>3</sub>"))
    (is (str/includes? (mt/math "m_{ij}") "<sub>ij</sub>")))
  (testing "the campaign's own notation"
    (is (str/includes? (mt/math "uE({3,5,9,15}) = 17/45") "u<sub>E</sub>"))
    (is (str/includes? (mt/math "uC is constant") "u<sub>C</sub>")))
  (testing "whole words only"
    (is (str/includes? (mt/math "sum of 1/m") "∑"))
    (is (not (str/includes? (mt/math "the consumed budget") "∑"))
        "a word containing 'sum' is not an operator"))
  (testing "prose output is still valid markup"
    (let [out (mt/math "if a < b and c > d & e then sum x_1 >= 2")]
      (is (not (re-find #"(?<!&)<(?!/?(sub|sup|b|i|tt|span))" out))
          "every angle bracket is either an entity or a tag we opened"))))

(deftest headings-and-dim-text-escape-too
  (is (= "<b>A &amp; B</b>" (mt/heading "A & B")))
  (is (str/includes? (mt/dim "x < y") "&lt;"))
  (is (str/includes? (mt/dim "x < y") "foreground")))
