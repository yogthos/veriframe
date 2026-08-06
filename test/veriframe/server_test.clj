;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.server-test
  "The vendored ring adapter's request reader."
  (:require [clojure.test :refer [deftest testing is]]
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
