(ns datachannel.sctp-timeout-resends-packet-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest timeout-resends-packet-test
  (testing "Timeout Resends Packet"
    (let [now 1000
          state {:remote-tsn 0 :remote-ver-tag 2222 :next-tsn 1000 :ssn 0 :state :established :timers {}}
          payload (.getBytes "Hello SCTP" "UTF-8")
          res1 (core/send-data state payload 0 :webrtc/string now)
          state1 (:new-state res1)
          data-packet (first (:network-out res1))]
      (is data-packet "Should send a DATA packet")
      (is (= 1 (count (:tx-queue state1))) "Should have unacknowledged data in tx-queue")
      (is (contains? (:timers state1) :t3-rtx) "Should start t3-rtx timer")

      (let [timer (get-in state1 [:timers :t3-rtx])
            next-now (:expires-at timer)
            res2 (core/handle-timeout state1 :t3-rtx next-now)
            state2 (:new-state res2)
            retx-packet (first (:network-out res2))]
        (is retx-packet "Should resend DATA packet on timeout")
        (is (= :data (:type (first (:chunks retx-packet)))) "Should have a DATA chunk")
        (is (= 1 (count (:tx-queue state2))) "Should still have unacknowledged data in tx-queue")
        (is (contains? (:timers state2) :t3-rtx) "Should restart t3-rtx timer")
        (is (> (:delay (get-in state2 [:timers :t3-rtx])) (:delay timer)) "Should increase backoff delay")))))
