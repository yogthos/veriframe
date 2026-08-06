;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.agent.claims
  "Run-scoped claim registry: the control-plane twin of the shared-artifact log.

  The artifact log (store/artifacts.clj) shares what an engine CONFIRMED,
  after the fact, FTS-ranked into a branch's context. This registry stops the
  expensive part of a slow verification from running twice at all: two
  branches reaching the same claim in the same run share one verdict instead
  of paying the judge or the engine twice for it. Fast checks stay duplicated
  on purpose — they are cheap, and duplication preserves branch independence.

  The locking follows UCLA FirstProof's claim protocol: a claim is HELD from
  the moment a branch claims it until a verdict lands, so no two workers ever
  verify the same statement concurrently. A held claim is released only when
  the verification never ran or died as a process failure. A verdict — even a
  failing one — is recorded and stays: a failed verdict is information the
  next branch should get, not a slot to re-race. Their analysis credited this
  rule with keeping concurrent verifiers from burning budget on each other's
  mistakes.

  The registry is an atom created once per beam run and threaded through ctx;
  a nil registry (no run) means tools behave exactly as before."
  (:require [clojure.string :as str]))

(defn new-registry
  "A fresh, run-scoped claim registry. Created once per beam run so every
  branch shares one map; the atom is the concurrency boundary."
  []
  (atom {}))

(defn- normalize
  "Spelling-level normalization only, matching consensus/normalize-claim: case
  and punctuation group two phrasings of the same claim together, everything
  else is untouched. A claim that groups with another branch's confirmed
  artifact in consensus must also dedup here."
  [claim]
  (-> (str/lower-case (or claim ""))
      (str/replace #"[^a-z0-9]+" " ")
      str/trim))

(defn try-claim!
  "Atomic check-and-claim on the normalized claim.

  Returns :claimed when this branch owns the claim — freshly claimed, or a
  re-claim by the branch that already holds it, which is allowed to run again
  (fixing an encoding after a failed verdict must not be locked out). Returns
  {:status :held|:done :holder <branch-id> :outcome ...} when ANOTHER branch
  owns it: :held while its verification is in flight, :done once it has
  settled, with the settled outcome (:confirmed and the artifact, or :failed
  and the reason) attached so the caller can serve it without spending its
  own call.

  The swap! is the atomicity: check-and-claim is one compare-and-set, so two
  branches racing the same claim cannot both win."
  [registry branch-id claim]
  (let [k (normalize claim)
        m (swap! registry
                 (fn [m]
                   (let [entry (get m k)]
                     (if (and entry (not= branch-id (:holder entry)))
                       m
                       (assoc m k {:status :held :holder branch-id})))))]
    (let [entry (get m k)]
      (if (= branch-id (:holder entry))
        :claimed
        entry))))

(defn complete!
  "Settle a claim this branch holds with a verdict. `outcome` is :confirmed or
  :failed; the last argument is the artifact map that substantiates a
  confirmation, or the reason string behind a failure. The holder's id stays
  on the entry so a later branch can cite where the outcome came from.

  A settled claim stays settled for the rest of the run — the UCLA rule, held
  even on failure."
  [registry branch-id claim outcome artifact-or-reason]
  (swap! registry assoc (normalize claim)
         (merge {:status :done :holder branch-id :outcome outcome}
                (if (= :confirmed outcome)
                  {:artifact artifact-or-reason}
                  {:reason artifact-or-reason}))))

(defn release!
  "Drop an in-flight claim WITHOUT a verdict, so another branch can claim it.
  Only the holder may release, and only for the process-failure case: the
  verification never ran, or died as an engine error. A verdict — even a
  failing one — is never released here; that is what `complete!` is for."
  [registry branch-id claim]
  (let [k (normalize claim)]
    (swap! registry (fn [m]
                      (if (= branch-id (get-in m [k :holder]))
                        (dissoc m k)
                        m)))))
