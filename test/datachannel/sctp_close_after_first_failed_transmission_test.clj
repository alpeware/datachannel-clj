(ns datachannel.sctp-close-after-first-failed-transmission-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest close-connection-after-first-failed-transmission-test
  (testing "Close Connection After First Failed Transmission"
    (let [now 1000
          state {:remote-tsn 0 :remote-ver-tag 2222 :next-tsn 1000 :ssn 0 :state :established :timers {} :max-retransmissions 0 :flight-size 0 :recv-queue []}
          payload (.getBytes "Hello SCTP" "UTF-8")
          res1 (core/send-data state payload 0 :webrtc/string now)
          state1 (:new-state res1)
          data-packet (first (:network-out res1))]
      (is data-packet "Should send a DATA packet")
      (is (= 1 (count (get-in state1 [:streams 0 :send-queue]))) "Should have unacknowledged data in send-queue")
      (is (contains? (:timers state1) :t3-rtx) "Should start t3-rtx timer")

      (let [timer (get-in state1 [:timers :t3-rtx])
            next-now (:expires-at timer)
            res2 (core/handle-timeout state1 :t3-rtx next-now)
            state2 (:new-state res2)]
        (is (= :closed (:state state2)) "Should close connection after first failed transmission")
        (is (= 1 (count (:app-events res2))) "Should emit app event")
        (is (= :on-error (:type (first (:app-events res2)))) "App event should be on-error")
        (is (= :max-retransmissions (:cause (first (:app-events res2)))) "App event cause should be max-retransmissions")))))
