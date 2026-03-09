(ns datachannel.sctp-recover-on-last-retransmission-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest recover-on-last-retransmission-test
  (testing "Recovers on last retransmission"
    (let [now-ms 1000
          state {:state :established
                 :max-retransmissions 3
                 :mtu 1200
                 :cwnd 1200
                 :flight-size 100
                 :pending-control-chunks []
                 :timers {:t3-rtx {:expires-at now-ms :delay 1000}}
                 :streams {0 {:send-queue [{:chunk {:type :data :flags 3 :stream-id 0 :seq-num 0 :payload (byte-array 10)}
                                            :retries 2
                                            :sent? true
                                            :tsn 100}]}}}
          result (core/handle-timeout state :t3-rtx now-ms)]
      (is (= :established (get-in result [:new-state :state])))
      (is (= 3 (get-in result [:new-state :streams 0 :send-queue 0 :retries])))
      (is (= true (get-in result [:new-state :streams 0 :send-queue 0 :sent?])))
      (is (= :data (get-in result [:network-out 0 :chunks 0 :type])))
      (is (= [] (:app-events result))))))
