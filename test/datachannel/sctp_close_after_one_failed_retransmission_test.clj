(ns datachannel.sctp-close-after-one-failed-retransmission-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest close-connection-after-one-failed-retransmission-test
  (testing "Close Connection After One Failed Retransmission"
    (let [now 1000
          state {:remote-tsn 0 :remote-ver-tag 2222 :next-tsn 1000 :ssn 0 :state :established :timers {} :max-retransmissions 1 :flight-size 0 :recv-queue []}
          payload (.getBytes "Hello SCTP" "UTF-8")
          res1 (core/send-data state payload 0 :webrtc/string now)
          state1 (:new-state res1)
          data-packet (first (:network-out res1))]
      (is data-packet "Should send a DATA packet")
      (is (= 1 (count (get-in state1 [:streams 0 :send-queue]))) "Should have unacknowledged data in send-queue")
      (is (contains? (:timers state1) :sctp/t3-rtx) "Should start t3-rtx timer")

      (let [timer (get-in state1 [:timers :sctp/t3-rtx])
            next-now (:expires-at timer)
            res2 (core/handle-timeout state1 :sctp/t3-rtx next-now)
            state2 (:new-state res2)]
        (is (= :established (:state state2)) "Should remain established after first timeout")
        (is (= 1 (count (:network-out res2))) "Should resend DATA packet")

        (let [timer2 (get-in state2 [:timers :sctp/t3-rtx])
              next-now2 (:expires-at timer2)
              res3 (core/handle-timeout state2 :sctp/t3-rtx next-now2)
              state3 (:new-state res3)]

          (is (= :closed (:state state3)) "Should close connection after one failed retransmission")
          (is (= 2 (count (:app-events res3))) "Should emit app event")
          (is (= :on-error (:type (first (:app-events res3)))) "App event should be on-error")
          (is (= :max-retransmissions (:cause (first (:app-events res3)))) "App event cause should be max-retransmissions"))))))
