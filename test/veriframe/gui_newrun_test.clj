;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui-newrun-test
  "Turning the new-run form into a POST body. Pure, because the interesting
  part is what happens to half-filled and mistyped fields — and a GTK entry
  hands you a string for everything, including the numbers."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [veriframe.gui.newrun :as newrun]))

(deftest a-problem-statement-is-the-one-required-field
  (testing "the minimum viable run"
    (is (= {:problem "prove that 2+2=4"}
           (:body (newrun/request {:problem "prove that 2+2=4"})))))
  (testing "blank, whitespace, and missing are the same mistake"
    (doseq [p [nil "" "   " "\n\t "]]
      (let [r (newrun/request {:problem p})]
        (is (nil? (:body r)))
        (is (= "a problem statement is required" (:error r))))))
  (testing "the statement is sent as typed, newlines and all"
    (let [p "Line one.\n\nLine two with  spacing."]
      (is (= p (get-in (newrun/request {:problem p}) [:body :problem]))
          "no trimming inside — only the blank check trims"))))

(deftest optional-knobs-are-omitted-when-left-empty
  (testing "empty strings mean 'use the server default', not zero"
    (let [b (:body (newrun/request {:problem "p" :max-turns "" :beam-width "  "
                                    :seed-run ""}))]
      (is (= {:problem "p"} b) "an untouched field never reaches the wire")))
  (testing "filled knobs go out under the names the API documents"
    (is (= {:problem "p" :max_turns 300 :beam_width 3 :seed_run "abc-123"}
           (:body (newrun/request {:problem "p" :max-turns "300"
                                   :beam-width "3" :seed-run "abc-123"})))))
  (testing "numbers already typed as numbers are accepted"
    (is (= 300 (get-in (newrun/request {:problem "p" :max-turns 300})
                       [:body :max_turns])))))

(deftest a-mistyped-number-is-refused-rather-than-silently-dropped
  ;; Dropping it would start a real run on the default budget while the user
  ;; believed they had asked for something else — the same failure the API's
  ;; own underscore/hyphen handling exists to prevent.
  (testing "non-numeric"
    (let [r (newrun/request {:problem "p" :max-turns "lots"})]
      (is (nil? (:body r)))
      (is (= "max turns must be a positive whole number" (:error r)))))
  (testing "zero and negative are not budgets"
    (doseq [v ["0" "-5"]]
      (is (some? (:error (newrun/request {:problem "p" :max-turns v}))))
      (is (some? (:error (newrun/request {:problem "p" :beam-width v}))))))
  (testing "the beam width says which field is wrong"
    (is (= "beam width must be a positive whole number"
           (:error (newrun/request {:problem "p" :beam-width "wide"}))))))

(deftest summary-describes-what-will-be-sent
  (testing "defaults are named as defaults, not guessed at"
    (is (= "300 turns · beam 3" (newrun/summary {:max_turns 300 :beam_width 3})))
    (is (= "server defaults" (newrun/summary {:problem "p"}))))
  (testing "a seed is worth saying out loud — it carries prior artifacts in"
    (is (= "beam 2 · seeded from 05ecb88a"
           (newrun/summary {:beam_width 2 :seed_run "05ecb88a-52d7-4d72-acaf"})))))

(deftest the-model-and-thinking-level-are-part-of-the-form
  ;; Putting a run on a different arm used to mean restarting the server with
  ;; a different HARNESS_MODEL, which kills whatever run is in flight. Both
  ;; are per-run now, so the form collects them.
  (testing "left alone, neither reaches the wire"
    (is (= {:problem "p"}
           (:body (newrun/request {:problem "p" :model "" :reasoning-effort "  "})))))

  (testing "a chosen model and effort go out under the API's names"
    (is (= {:problem "p" :model "deepseek-v4-pro" :reasoning_effort "high"}
           (:body (newrun/request {:problem "p" :model "deepseek-v4-pro"
                                   :reasoning-effort "high"})))))

  (testing "surrounding whitespace from a pasted model name is trimmed"
    (is (= "deepseek-v4-pro"
           (get-in (newrun/request {:problem "p" :model "  deepseek-v4-pro  "})
                   [:body :model]))))

  (testing "the summary names the arm, since that is what makes two runs comparable"
    (is (str/includes? (newrun/summary {:problem "p" :model "deepseek-v4-pro"})
                       "deepseek-v4-pro"))
    (is (str/includes? (newrun/summary {:problem "p" :model "deepseek-v4-pro"
                                        :reasoning_effort "high"})
                       "high"))
    (is (= "server defaults" (newrun/summary {:problem "p"}))
        "and says nothing when nothing was chosen")))
