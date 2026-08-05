;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.pool-test
  "The warm-session pool, tested without Lean.

  Every case here is about the bookkeeping, which is where the bugs would be:
  two branches must not get the same session, a slot still importing must not be
  dropped on the floor, and a slot that failed to warm must fall through to the
  caller's own session rather than surfacing as an error. None of that needs a
  Mathlib import, so these run offline in milliseconds."
  (:require [clojure.test :refer [deftest is testing]]
            [veriframe.engine.lean-pool :as pool]))

;; A stand-in for a session. The pool only ever asks lean-repl/alive? about it,
;; so a map with the same shape is enough.
(defn- fake-session [id] {:id id :alive (atom true)})

(defn- warm
  "A slot whose import has already finished."
  [v]
  (doto (promise) (deliver v)))

(defn- seed! [entries]
  (pool/shutdown!)
  (#'pool/reset-pool! entries))

(deftest checkout-returns-a-warm-session
  (seed! [(warm {:ok true :session (fake-session :a)})])
  (is (= :a (:id (pool/checkout! 0))))
  (is (nil? (pool/checkout! 0)) "the pool is empty once its only slot is taken"))

(deftest a-session-is-handed-out-once
  ;; Two branches sharing one Lean session would be silent corruption: the
  ;; session carries an environment, so one branch's declarations would be
  ;; visible to the other and a `sorry` could leak across an isolation boundary.
  (seed! (mapv #(warm {:ok true :session (fake-session %)}) [:a :b :c]))
  (let [taken (repeatedly 5 #(pool/checkout! 0))]
    (is (= [:a :b :c] (keep :id taken)))
    (is (= 3 (count (distinct (keep :id taken)))))))

(deftest concurrent-checkout-never-double-issues
  (seed! (mapv #(warm {:ok true :session (fake-session %)}) (range 20)))
  (let [got (->> (repeatedly 30 (fn [] (future (pool/checkout! 0))))
                 doall
                 (map deref)
                 (keep :id))]
    (is (= 20 (count got)))
    (is (= 20 (count (distinct got))) "no session was issued to two callers")))

(deftest a-failed-warm-falls-through
  ;; nil means "build your own", which the tool path already handles. Returning
  ;; an error instead would turn a slow path into a broken one.
  (seed! [(warm {:ok false :error "no toolchain"})])
  (is (nil? (pool/checkout! 0))))

(deftest a-still-importing-slot-is-put-back
  ;; The failure this guards: claim a pending slot, fail to wait, drop it. The
  ;; import keeps running, the session is never disposed, and it leaks for the
  ;; life of the process.
  (let [p (promise)]
    (seed! [p])
    (is (nil? (pool/checkout! 0)) "does not block past the wait budget")
    (is (= 1 (pool/pending-count)) "the slot is still in the pool")
    (deliver p {:ok true :session (fake-session :late)})
    (is (= :late (:id (pool/checkout! 0))) "and is usable once the import lands")))

(deftest counts_report_warm_and_pending_separately
  (let [p (promise)]
    (seed! [(warm {:ok true :session (fake-session :a)}) p])
    (testing "health must distinguish installed from ready"
      (is (= 1 (pool/warmed-count)))
      (is (= 1 (pool/pending-count))))
    (deliver p {:ok true :session (fake-session :b)})
    (is (= 2 (pool/warmed-count)))
    (is (= 0 (pool/pending-count)))))

(deftest shutdown-disposes-and-empties
  (seed! (mapv #(warm {:ok true :session (fake-session %)}) [:a :b]))
  (is (= 2 (pool/shutdown!)))
  (is (zero? (pool/warmed-count)))
  (is (nil? (pool/checkout! 0))))
