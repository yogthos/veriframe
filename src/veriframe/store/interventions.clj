;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.store.interventions
  "The human directive queue.

  A directive applies at the next branch boundary, never mid-turn: a branch
  inside a provider call or a Lean tactic is not in a state anyone should
  mutate. That means a UI has to show pending versus applied honestly rather
  than pretending a click took effect, which is why `status` and `disposition`
  are stored rather than inferred.

  Abort is the exception and does not come through here. It goes to the
  supervisor, because a run that is wedged is exactly the run that will never
  reach another boundary to drain a queue at."
  (:require [clojure.data.json :as json]
            [jdbc.core :as jdbc]
            [veriframe.store.db :as db]
            [veriframe.store.journal :as journal]))

(def kinds
  "What a human may ask for. Each is applied by the arbiter or the scheduler at
  a boundary; nothing here mutates a branch directly."
  {"message" "Inject text into a branch's next turn."
   "review" "Tell a branch to cross-check and ship what it has."
   "cull" "Stop a branch. Refused if it is the last one running."
   "fork" "Open a sibling branch on a stated thesis."
   "extend" "Raise the run's turn cap."
   "pause" "Stop scheduling new turns; in-flight turns finish."
   "resume" "Resume scheduling."})

(defn submit!
  [conn run-id {:keys [branch-id kind payload issued-by]}]
  (when-not (contains? kinds kind)
    (throw (ex-info (str "Unknown intervention kind: " kind)
                    {:kind kind :known (sort (keys kinds))})))
  (let [id (db/with-writer
             (db/execute! conn
                            ["INSERT INTO interventions (run_id, branch_id, kind, payload,
                                                         issued_by, status, created_at)
                              VALUES (?, ?, ?, ?, ?, 'pending', ?)"
                             run-id branch-id kind
                             (if (string? payload) payload (json/write-str (or payload {})))
                             (or issued-by "human") (db/now)])
             (db/last-insert-id conn))]
    (journal/note! conn run-id :intervention-submitted
                   {:branch-id branch-id :data {:id id :kind kind}})
    id))

(defn pending
  "Directives waiting for a boundary. Scoped to a branch when given one, plus
  the run-wide ones that apply to every branch."
  ([conn run-id]
   (db/fetch conn ["SELECT * FROM interventions
                      WHERE run_id = ? AND status = 'pending' ORDER BY id" run-id]))
  ([conn run-id branch-id]
   (db/fetch conn ["SELECT * FROM interventions
                      WHERE run_id = ? AND status = 'pending'
                        AND (branch_id = ? OR branch_id IS NULL) ORDER BY id"
                     run-id branch-id])))

(defn resolve!
  "Record what the arbiter or scheduler did with a directive. `status` is
  applied or rejected, and `disposition` says why — a directive refused because
  it would cull the last branch is a different thing from one that landed, and
  the UI has to be able to tell them apart."
  [conn run-id id status disposition turn]
  (db/with-writer
    (db/execute! conn
                   ["UPDATE interventions SET status = ?, disposition = ?, applied_at_turn = ?
                     WHERE id = ?"
                    (name status) disposition turn id]))
  (journal/note! conn run-id :intervention-resolved
                 {:turn turn :data {:id id :status status :disposition disposition}}))

(defn history [conn run-id]
  (db/fetch conn ["SELECT * FROM interventions WHERE run_id = ? ORDER BY id" run-id]))
