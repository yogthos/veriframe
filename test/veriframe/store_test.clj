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

(ns veriframe.store-test
  "Phase 0 storage, kept as tests rather than only as smoke probes.

  The migration statement-count check is the one that matters. db.sqlite/query
  calls sqlite3_prepare_v2 with a null tail pointer, so a migration written as
  one multi-statement string would execute only its first statement and report
  no error. Counting objects created against statements written is how that
  failure mode stays loud."
  (:require [clojure.test :refer [deftest testing is]]
            [jdbc.core :as jdbc]
            [veriframe.store.db :as db]
            [veriframe.store.migrations :as migrations]))

(defmacro with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

(deftest migrations-apply
  (with-db [c]
    (is (= (count migrations/migrations) (db/schema-version c)))
    (is (every? (set (db/table-names c))
                ["runs" "branches" "turns" "artifacts" "failures"
                 "gate_firings" "interventions" "events"]))))

(deftest migrations-are-idempotent
  (with-db [c]
    (let [v (db/schema-version c)
          tables (db/table-names c)]
      (db/migrate! c)
      (is (= v (db/schema-version c)))
      (is (= tables (db/table-names c))))))

(deftest every-migration-statement-runs
  (testing "no migration hides statements behind a multi-statement string"
    ;; Every entry must be a vector of single statements. A statement holding
    ;; a second one after a semicolon would be silently dropped by
    ;; sqlite3_prepare_v2, so reject that shape outright rather than trusting
    ;; that nobody writes it.
    (doseq [[i statements] (map-indexed vector migrations/migrations)]
      (is (vector? statements) (str "migration v" (inc i) " must be a vector"))
      (doseq [sql statements]
        (is (not (re-find #";\s*\S" (clojure.string/replace sql #"--[^\n]*" "")))
            (str "migration v" (inc i) " has a statement containing a `;` followed by"
                 " more SQL, which sqlite3_prepare_v2 would silently drop:\n" sql))))))

(deftest fts5-is-available-through-the-ffi-binding
  ;; Distinct from the sqlite3 CLI having FTS5. The failure mode is a
  ;; migration that throws at startup.
  (let [c (db/connect ":memory:")]
    (try
      (is (db/fts5-available? c))
      (finally (db/close c)))))

(deftest failures-fts-round-trips
  (with-db [c]
    (jdbc/execute! c ["INSERT INTO failures_fts (claim, reason) VALUES (?, ?)"
                      "sidon set of size 24 exists" "z3 returned unsat"])
    (jdbc/execute! c ["INSERT INTO failures_fts (claim, reason) VALUES (?, ?)"
                      "the coloring is schur-good" "monochromatic triple at 3+3=6"])
    (is (= 1 (count (jdbc/fetch c ["SELECT claim FROM failures_fts WHERE failures_fts MATCH ?"
                                   "sidon"]))))
    (is (= 1 (count (jdbc/fetch c ["SELECT claim FROM failures_fts WHERE failures_fts MATCH ?"
                                   "monochromatic"]))))
    (is (empty? (jdbc/fetch c ["SELECT claim FROM failures_fts WHERE failures_fts MATCH ?"
                               "lean"])))))
