(ns datachannel.sctp-send-many-fragmented-messages-with-limited-rtx-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest send-many-fragmented-messages-with-limited-rtx-test
  (testing "Send Many Fragmented Messages With Limited Rtx"
    (let [now 1000
          state {:remote-tsn 0 :remote-ver-tag 2222 :next-tsn 1000 :ssn 0 :state :established :timers {} :max-retransmissions 10 :flight-size 0 :recv-queue []}
          payload (byte-array 3000 (byte 65))
          ;; Set max message size > 3000 to allow fragmentation, but lower than MTU * fragments
          state (assoc state :mtu 1200)
          res1 (core/send-data state payload 0 :webrtc/string now)
          state1 (:new-state res1)

          ;; Now artificially set max-retransmits on the queued items to 0
          q (get-in state1 [:streams 0 :send-queue])
          new-q (mapv #(assoc % :max-retransmits 0) q)
          state1 (assoc-in state1 [:streams 0 :send-queue] new-q)

          timer (get-in state1 [:timers :t3-rtx])
          next-now (:expires-at timer)

          ;; Handle timeout
          res2 (core/handle-timeout state1 :t3-rtx next-now)
          state2 (:new-state res2)
          net-out (:network-out res2)]

      (is (= :established (:state state2)) "Should remain established after dropping message")
      (is (empty? (get-in state2 [:streams 0 :send-queue])) "All fragments should be dropped by timer")
      (is (= 1 (count net-out)) "Should send a packet")
      (is (= :forward-tsn (:type (first (:chunks (first net-out))))) "Should send a FORWARD-TSN chunk")
      (is (= 1002 (:new-cumulative-tsn (first (:chunks (first net-out))))) "FORWARD-TSN should advance TSN past all dropped fragments")
      (is (= [{:stream-id 0 :stream-sequence 0}] (:streams (first (:chunks (first net-out))))) "FORWARD-TSN should correctly populate streams")
      )))
