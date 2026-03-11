(ns datachannel.sctp-discards-messages-with-low-lifetime-if-must-buffer-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest discards-messages-with-low-lifetime-if-must-buffer-test
  (testing "Discards Messages With Low Lifetime If Must Buffer"
    (let [now 1000
          state {:remote-tsn 0 :remote-ver-tag 2222 :next-tsn 1000 :ssn 0 :state :established :timers {} :max-retransmissions 10 :flight-size 0 :recv-queue []}
          payload (byte-array 10 (byte 65))
          state (assoc state :mtu 1200
                       :cwnd 10
                       :flight-size 10
                       :data-channels {0 {:max-packet-life-time 50}})
          res1 (core/send-data state payload 0 :webrtc/string now)
          state1 (:new-state res1)

          q (get-in state1 [:streams 0 :send-queue])
          _ (is (= 1 (count q)))

          state1 (assoc state1 :cwnd 1200 :flight-size 0)

          next-now (+ now 100)

          res2 (core/packetize state1 [] next-now)
          state2 (:new-state res2)
          net-out (:network-out res2)]
      (is (= :established (:state state2)) "Should remain established")
      (is (empty? (get-in state2 [:streams 0 :send-queue])) "Message should be dropped")
      (is (= 1 (count net-out)) "Should send a packet")
      (is (= :forward-tsn (:type (first (:chunks (first net-out))))) "Should send FORWARD-TSN"))))
