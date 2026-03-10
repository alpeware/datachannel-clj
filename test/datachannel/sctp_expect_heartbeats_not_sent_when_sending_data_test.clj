(ns datachannel.sctp-expect-heartbeats-not-sent-when-sending-data-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest expect-heartbeats-not-sent-when-sending-data-test
  (testing "Expect Heartbeats Not Sent When Sending Data"
    (let [now 1000
          state {:state :established
                 :remote-ver-tag 2222
                 :local-ver-tag 1111
                 :src-port 5000
                 :dst-port 5001
                 :next-tsn 1000
                 :remote-tsn 0
                 :ssn 0
                 :mtu 1200
                 :max-message-size 65536
                 :heartbeat-interval 30000
                 :cwnd 1200
                 :flight-size 0
                 :timers {:sctp/t-heartbeat {:expires-at (+ now 100)}}}

          ;; Send data before heartbeat expires
          payload (byte-array [1 2 3])
          res1 (core/send-data state payload 0 :webrtc-binary (+ now 50))
          state2 (:new-state res1)

          ;; Heartbeat timer should have been updated by send-data to (+ now 50) + 30000 = 31050
          hb-timer (get-in state2 [:timers :sctp/t-heartbeat :expires-at])]

      (is (not= nil hb-timer) "Heartbeat timer should still exist")
      (is (= 31050 hb-timer) "Heartbeat timer should be reset to current time + interval"))))
