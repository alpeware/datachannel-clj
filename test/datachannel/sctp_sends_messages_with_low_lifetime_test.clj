(ns datachannel.sctp-sends-messages-with-low-lifetime-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest sends-messages-with-low-lifetime-test
  (testing "Sends Messages With Low Lifetime"
    (let [now 1000
          state {:remote-tsn 0 :remote-ver-tag 2222 :next-tsn 1000 :ssn 0 :state :established :timers {} :max-retransmissions 10 :flight-size 0 :recv-queue []}
          payload (byte-array 10 (byte 65))
          state (assoc state :mtu 1200
                       :data-channels {0 {:max-packet-life-time 50}})
          res1 (core/send-data state payload 0 :webrtc/string now)
          state1 (:new-state res1)

          q (get-in state1 [:streams 0 :send-queue])
          _ (is (= 1 (count q)))

          net-out (:network-out res1)]

      (is (= :established (:state state1)) "Should remain established")
      (is (= 1 (count net-out)) "Should send a packet")
      (is (= :data (:type (first (:chunks (first net-out))))) "Should send DATA"))))
