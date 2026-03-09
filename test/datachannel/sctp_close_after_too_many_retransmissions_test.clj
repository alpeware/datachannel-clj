(ns datachannel.sctp-close-after-too-many-retransmissions-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest close-connection-after-too-many-retransmissions-test
  (testing "Connection closes after max-retransmissions is reached on T3-rtx"
    (let [now-ms 1000
          state {:state :established
                 :max-retransmissions 3
                 :mtu 1200
                 :cwnd 1200
                 :flight-size 100
                 :pending-control-chunks []
                 :timers {:sctp/t3-rtx {:expires-at now-ms :delay 1000}}
                 :streams {0 {:send-queue [{:chunk {:type :data :flags 3 :stream-id 0 :seq-num 0 :payload (byte-array 10)}
                                            :retries 3
                                            :sent? true
                                            :tsn 100}]}}}
          result (core/handle-timeout state :sctp/t3-rtx now-ms)]
      (is (= :closed (get-in result [:new-state :state])))
      (is (= [{:type :on-error :cause :max-retransmissions}] (:app-events result)))
      (is (= :abort (get-in result [:network-out 0 :chunks 0 :type]))))))
