;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.store.db
  "SQLite connection and migration runner (jolt-lang/db over libsqlite3).

  Writes funnel through one connection on purpose. Five branches appending to
  the journal concurrently means concurrent FFI calls into libsqlite3, and
  while libsqlite3's default threading mode is serialized, the FFI binding's
  own safety under Chez threads is unproven. A single writer sidesteps the
  question; readers open their own connections."
  (:require [clojure.string :as str]
            [jdbc.core :as jdbc]
            [veriframe.store.migrations :as migrations]))

(defn connect
  "Open `path` (a file, or \":memory:\") and return a connection."
  [path]
  (jdbc/connection (str "sqlite:" path)))

(def ^:private conn-lock (Object.))

(defmacro with-conn
  "Serialize ALL access to the connection, reads included.

  Locking only writes was wrong and the full benchmark sweep is what proved it:
  every read also calls sqlite3_prepare_v2 on the same connection handle, and
  four concurrent problems times three branches is twelve threads preparing at
  once. The result was `sqlite prepare failed: not an error` on every run and
  then an invalid memory reference on close. The Phase 0 probe passed because
  it only exercised writers, which were the half already serialized.

  One lock for one handle. Concurrency lives in the branches and the engines,
  not in the store.

  This looks heavier than it is, and the obvious next move — a write queue
  drained by one thread, so reads stop waiting behind writes — was measured and
  declined. A journal write is ~2.5ms, an FTS read ~0.7ms under twelve
  concurrent writers, and a real run does about 3.6 writes per turn against a
  turn that averages 37 seconds because it contains a provider call. The store
  is roughly 0.027% of a turn.

  Buffering would trade the property this system leads with — everything
  appended as it happens, so a crashed run stays inspectable — plus a drainer
  that stops the journal silently if it dies, for a saving nothing can observe.
  See veriframe-clj-dpj for the numbers. Reopen it if the store ever appears in
  a profile, which realistically means dropping this lock first."
  [& body]
  `(locking conn-lock ~@body))

;; Retained so existing call sites keep meaning what they say; a write is just
;; an access that mutates.
(defmacro with-writer [& body] `(with-conn ~@body))

(defn fetch
  "A serialized read. Everything that reads the shared connection goes through
  here rather than calling jdbc directly."
  [conn q]
  (with-conn (jdbc/fetch conn q)))

(defn fetch-one [conn q]
  (with-conn (jdbc/fetch-one conn q)))

(defn execute!
  ([conn q] (with-conn (jdbc/execute! conn q)))
  ([conn q opts] (with-conn (jdbc/execute! conn q opts))))

(defn last-insert-id [conn]
  (with-conn (jdbc/last-insert-id conn)))

(defn now
  "An ISO-8601 timestamp. One function so every table sorts the same way."
  []
  (str (java.time.Instant/now)))

(defn close [conn]
  ;; jdbc.core's connection is a map carrying a :close thunk, not an object.
  (when-let [f (:close conn)] (f)))

(defn schema-version [conn]
  (or (-> (jdbc/fetch-one conn "PRAGMA user_version") vals first) 0))

(defn- set-schema-version! [conn n]
  ;; PRAGMA does not take bound parameters, so this is interpolated. n is an
  ;; index into a compiled-in vector, never user input.
  (jdbc/execute! conn (str "PRAGMA user_version = " (long n))))

(defn migrate!
  "Apply every migration past the current user_version. Idempotent: running it
  twice is a no-op. Returns the version now in effect."
  [conn]
  (let [applied (schema-version conn)
        pending (subvec migrations/migrations (min applied (count migrations/migrations)))]
    (doseq [[i statements] (map-indexed vector pending)]
      (let [version (+ applied i 1)]
        (doseq [sql statements]
          (try
            (jdbc/execute! conn sql)
            (catch Throwable e
              (throw (ex-info (str "Migration v" version " failed: " (ex-message e))
                              {:version version
                               :statement (subs sql 0 (min 120 (count sql)))}
                              e)))))
        (set-schema-version! conn version)))
    (schema-version conn)))

(defn open!
  "Connect, migrate, return the connection."
  [path]
  (doto (connect path) (migrate!)))

(defn table-names
  "Every user table and virtual table in the database, sorted. Used by the
  migration test to check that a multi-statement migration string did not
  silently execute only its first statement."
  [conn]
  (->> (jdbc/fetch conn "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name")
       (map :name)
       (remove #(str/includes? % "_fts_"))
       vec))

(defn fts5-available?
  "Whether the libsqlite3 the FFI binding loaded has FTS5 compiled in. This is
  a different question from whether the sqlite3 CLI has it, and the failure
  mode is a migration that throws at startup, so it is probed explicitly."
  [conn]
  (try
    (jdbc/execute! conn "CREATE VIRTUAL TABLE IF NOT EXISTS __fts5_probe USING fts5(x)")
    (jdbc/execute! conn "DROP TABLE IF EXISTS __fts5_probe")
    true
    (catch Throwable _ false)))
