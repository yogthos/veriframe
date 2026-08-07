;; veriframe - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program and the accompanying materials are made available under
;; the terms of the Eclipse Public License 2.0 which is available at
;; https://www.eclipse.org/legal/epl-2.0/
;;
;; SPDX-License-Identifier: EPL-2.0

(ns veriframe.gui-api-test
  "The GUI's HTTP client and poll fold. veriframe.gui.api is deliberately
  toolkit-free (http-client + json only), which is what lets the headless
  suite cover it without ever loading GTK."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.http-client :as http]
            [veriframe.gui.api :as api]))

(defn- ok [body] {:status 200 :body (json/write-str body)})

(deftest client-hits-the-documented-endpoints
  (let [calls (atom [])]
    (testing "GET endpoints and shapes"
      (with-redefs [http/get (fn [url & _]
                               (swap! calls conj url)
                               (ok {:runs [{:id "r1" :status "running"}]}))]
        (let [r (api/list-runs "http://x:1")]
          (is (= "http://x:1/v1/runs" (last @calls)))
          (is (:ok r))
          (is (= "r1" (-> r :body :runs first :id)))))
      (with-redefs [http/get (fn [url & _]
                               (swap! calls conj url)
                               (ok {:run_id "r1" :events [{:id 7 :kind "turn"}]}))]
        (api/journal-since "http://x:1" "r1" 42 100)
        (is (= "http://x:1/v1/runs/r1/journal?since=42&limit=100" (last @calls)))))
    (testing "interventions carry branch, kind, payload"
      (with-redefs [http/post (fn [url opts]
                                (swap! calls conj [url (:body opts)])
                                (ok {:id 1 :status "pending"}))]
        (api/intervene! "http://x:1" "r1" {:branch-id "B1" :kind "message"
                                           :payload "try modulus 25"})
        (let [[url body] (last @calls)]
          (is (= "http://x:1/v1/runs/r1/interventions" url))
          (is (str/includes? body "B1"))
          (is (str/includes? body "try modulus 25")))))
    (testing "resume includes max_turns only when extending"
      (with-redefs [http/post (fn [url opts]
                                (swap! calls conj [url (:body opts)])
                                (ok {:status "resuming"}))]
        (api/resume! "http://x:1" "r1")
        (is (not (str/includes? (second (last @calls)) "max_turns")))
        (api/resume! "http://x:1" "r1" 400)
        (is (str/includes? (second (last @calls)) "400"))))
    (testing "a dead server is a value, not a throw"
      (with-redefs [http/get (fn [& _] (throw (ex-info "connection refused" {})))]
        (let [r (api/list-runs "http://x:1")]
          (is (false? (:ok r)))
          (is (str/includes? (:error r) "refused")))))))

(deftest poll-fold-advances-cursor-and-backs-off
  (testing "events advance the cursor and reset the interval"
    (let [s (api/poll-step {:cursor 10 :interval-ms 24000}
                           {:ok true :body {:events [{:id 11} {:id 12}]}})]
      (is (= 12 (:cursor s)))
      (is (= api/base-interval-ms (:interval-ms s)))
      (is (true? (:connected? s)))
      (is (= [{:id 11} {:id 12}] (:events s)))))
  (testing "an empty batch keeps the cursor"
    (is (= 10 (:cursor (api/poll-step {:cursor 10 :interval-ms 1500}
                                      {:ok true :body {:events []}})))))
  (testing "failure doubles the interval up to the cap and marks disconnected"
    (let [s1 (api/poll-step {:cursor 10 :interval-ms api/base-interval-ms}
                            {:ok false :error "refused"})
          s2 (api/poll-step s1 {:ok false :error "refused"})]
      (is (false? (:connected? s1)))
      (is (= 10 (:cursor s2)) "the cursor survives an outage")
      (is (> (:interval-ms s2) (:interval-ms s1)))
      (is (<= (:interval-ms (nth (iterate #(api/poll-step % {:ok false}) s2) 10))
              api/max-backoff-ms)))))
