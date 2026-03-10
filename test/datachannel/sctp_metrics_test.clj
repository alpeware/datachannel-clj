(ns datachannel.sctp-metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest initial-metrics-are-unset-test
  (testing "Initial Metrics Are Unset"
    (let [state (@#'core/create-connection {} true)
          metrics (:metrics state)]
      (is (not (nil? metrics)) "Metrics map should be initialized")
      (is (= 0 (:tx-packets metrics)) "tx-packets should be 0")
      (is (= 0 (:rx-packets metrics)) "rx-packets should be 0")
      (is (= 0 (:tx-bytes metrics)) "tx-bytes should be 0")
      (is (= 0 (:rx-bytes metrics)) "rx-bytes should be 0")
      (is (= 0 (:retransmissions metrics)) "retransmissions should be 0")
      (is (= 0 (:unacked-data metrics)) "unacked-data should be 0"))))
