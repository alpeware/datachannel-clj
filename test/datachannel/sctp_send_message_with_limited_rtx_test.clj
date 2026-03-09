(ns datachannel.sctp-send-message-with-limited-rtx-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest send-message-with-limited-rtx-test
  (testing "Send Message With Limited Rtx"
    (let [now 1000
          state {:remote-tsn 0 :remote-ver-tag 2222 :next-tsn 1000 :ssn 0 :state :established :timers {} :max-retransmissions 10 :flight-size 0 :recv-queue []}
          payload (.getBytes "Hello SCTP" "UTF-8")
          res1 (core/send-data state payload 0 :webrtc/string now)
          state1 (:new-state res1)

          ;; Now artificially set max-retransmits on the queued item to 0
          q (get-in state1 [:streams 0 :send-queue])
          item (first q)
          item (assoc item :max-retransmits 0)
          state1 (assoc-in state1 [:streams 0 :send-queue] [item])

          timer (get-in state1 [:timers :sctp/t3-rtx])
          next-now (:expires-at timer)

          ;; Handle timeout
          res2 (core/handle-timeout state1 :sctp/t3-rtx next-now)
          state2 (:new-state res2)
          net-out (:network-out res2)]

      (is (= :established (:state state2)) "Should remain established after dropping message")
      (is (empty? (get-in state2 [:streams 0 :send-queue])) "Queue should be empty")
      (is (= 1 (count net-out)) "Should send a packet")
      (is (= :forward-tsn (:type (first (:chunks (first net-out))))) "Should send a FORWARD-TSN chunk")
      (is (= 1000 (:new-cumulative-tsn (first (:chunks (first net-out))))) "FORWARD-TSN should advance TSN")
      (is (= [{:stream-id 0 :stream-sequence 0}] (:streams (first (:chunks (first net-out))))) "FORWARD-TSN should correctly populate streams")
      )))
