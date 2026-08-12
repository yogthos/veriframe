;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.server-test
  "The vendored ring adapter's request reader, and the listen socket's
  close-on-exec."
  (:require [clojure.test :refer [deftest testing is]]
            [jolt.process :as p]
            [ring-chez.adapter :as adapter]))

(defn- request [body]
  (str "POST /v1/runs HTTP/1.1\r\n"
       "Content-Type: application/json\r\n"
       "Content-Length: " (alength (.getBytes body "UTF-8")) "\r\n"
       "\r\n"
       body))

(deftest content-length-is-octets-not-characters
  ;; A 3-byte em-dash decodes to one char. Judging completeness by char count
  ;; left the reader waiting for two bytes that had already arrived, so every
  ;; POST whose body carried multibyte UTF-8 hung until the client gave up.
  (testing "a multibyte body is complete when its octet count matches"
    (is (#'adapter/request-complete?
         (request "{\"note\": \"an em-dash — here\"}"))))
  (testing "an ascii body is complete"
    (is (#'adapter/request-complete? (request "{\"a\": 1}"))))
  (testing "a short body is incomplete"
    (let [r (request "{\"a\": 1}")]
      (is (not (#'adapter/request-complete? (subs r 0 (- (count r) 3)))))))
  (testing "unterminated headers are incomplete"
    (is (not (#'adapter/request-complete?
              "POST / HTTP/1.1\r\nContent-Length: 5\r\n")))))

(deftest a-subprocess-does-not-inherit-the-listen-socket
  ;; The listening socket is a raw fd from socket(2), and every process the
  ;; harness spawns — the Lean repl through `lake env`, prolog, octave — forks
  ;; from the server. Without close-on-exec each child holds a duplicate of it,
  ;; which lsof showed directly: jolt, lake and repl all on fd 4, same kernel
  ;; object, TCP 127.0.0.1:3985 (LISTEN).
  ;;
  ;; The port then stays bound for as long as ANY holder lives. Kill the server
  ;; while a Lean session lingers — which is what an abandoned run leaves behind,
  ;; since destroy-tree is a shutdown hook rather than a guarantee — and the
  ;; restart fails with address-in-use against a server that is already gone.
  ;;
  ;; Rebinding is the assertion because it is the consequence that bites.
  ;; SO_REUSEADDR lets a new socket past a TIME_WAIT, but not past another live
  ;; listener, so this fails exactly when a child is still holding one.
  (let [port 39187
        handler (fn [_] {:status 200 :headers {} :body "ok"})
        server (adapter/run-server handler {:port port})
        ;; Spawned while the server is up, so it forks with the fd open.
        child (p/process ["sleep" "20"] {})]
    (try
      ;; Asserted separately from the consequence, because the two fail for
      ;; different reasons and the first version could not tell them apart:
      ;; it passed on macOS and failed on Linux CI with nothing to say about
      ;; whether the flag had been set at all.
      (is (adapter/cloexec? (:socket server))
          "the listen fd is not marked FD_CLOEXEC — the mechanism itself failed")
      (adapter/stop-server server)
      (let [again (try {:ok true :server (adapter/run-server handler {:port port})}
                       (catch Throwable e {:ok false :error (ex-message e)}))]
        (is (:ok again)
            (str "port " port " is still held after the server stopped — a child "
                 "inherited the listen fd: " (:error again)))
        (when-let [s (:server again)] (adapter/stop-server s)))
      (finally
        (try (p/destroy-tree child) (catch Throwable _ nil))))))
