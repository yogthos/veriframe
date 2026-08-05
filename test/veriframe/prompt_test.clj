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

(ns veriframe.prompt-test
  "The prompt has to describe the tools that actually exist.

  This is not a style check. A model cannot call a tool it was never told about,
  so a tool missing from the prompt is a tool that does not exist as far as a run
  is concerned — it fails silently and looks like the model simply choosing not
  to use it. That is exactly what happened: the registry had 17 methods and the
  prompt documented 11, and the 6 missing ones were the entire Lean surface. The
  REPL session, the Mathlib premise index, tools/setup-lean.sh and the CI lean
  job all worked, and nothing could reach any of it. `/health` reported
  lean:true throughout.

  agent-test/tool-table-is-complete already pins the registry against a stray
  paren truncating the method table. This is the other half: the registry
  against the prompt."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [veriframe.agent.loop :as loop]
            [veriframe.agent.tools :as tools]
            [veriframe.engine.smt-templates :as templates]))

(deftest every-tool-is-documented
  (let [prompt (loop/system-prompt)
        undocumented (remove #(str/includes? prompt %) (tools/tool-names))]
    (is (empty? undocumented)
        (str "these tools are dispatched by run-tool but never mentioned in the"
             " prompt, so the model cannot call them: "
             (str/join ", " undocumented)))))

(deftest every-documented-tool-exists
  ;; The opposite drift, which is worse in one way: the model spends turns
  ;; calling something that lands on the :default method, and reads the failure
  ;; as its own mistake.
  (let [prompt (loop/system-prompt)
        known (set (tools/tool-names))
        ;; Names in the prompt are written as `name({args})`.
        mentioned (map second (re-seq #"(?m)^(\w+)\(\{?" prompt))
        phantom (remove known mentioned)]
    (is (empty? phantom)
        (str "the prompt documents tools that run-tool does not dispatch: "
             (str/join ", " phantom)))))

(deftest template-catalogue-is-substituted
  (let [prompt (loop/system-prompt)]
    (testing "the placeholder is replaced, not shipped to the model"
      (is (not (str/includes? prompt "{{templates}}"))))
    (testing "every template is named, so none has to be guessed"
      ;; Before this, the catalogue only appeared in the error returned after a
      ;; wrong guess, so a template the model never guessed was invisible.
      (doseq [t (keys templates/templates)]
        (is (str/includes? prompt t)
            (str "template `" t "` is registered but not in the prompt"))))))

(deftest engine-selection-guidance-is-present
  ;; The point of documenting all three engines is that the model picks by
  ;; problem shape. Assert the guidance survives an edit, since without it the
  ;; tool list is just a menu with no basis for choosing.
  (let [prompt (loop/system-prompt)]
    (doseq [marker ["Choosing an engine" "Prolog" "Z3" "Lean"]]
      (is (str/includes? prompt marker)
          (str "prompt lost its `" marker "` guidance")))))
