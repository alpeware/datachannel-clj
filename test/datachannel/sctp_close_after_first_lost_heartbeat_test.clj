(ns datachannel.sctp-close-after-first-lost-heartbeat-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest close-connection-after-first-lost-heartbeat-test
  (testing "Close Connection After First Lost Heartbeat"
    (let [now 1000000
          state {:state :established
                 :remote-ver-tag 1234
                 :local-ver-tag 5678
                 :next-tsn 1000
                 :ssn 0
                 :timers {:sctp/t-heartbeat {:expires-at (+ now 30000)}}
                 :heartbeat-interval 30000
                 :heartbeat-error-count 0
                 :rto-initial 1000
                 :max-retransmissions 0} ;; Setting to 0 so the first lost heartbeat closes it

          ;; 1. Expire t-heartbeat
          timer-expire-time (+ now 30000)
          res1 (core/handle-timeout state :sctp/t-heartbeat timer-expire-time)
          new-state1 (:new-state res1)
          network-out1 (:network-out res1)
          _ (is (= 1 (count network-out1)) "Should send a heartbeat packet")
          _ (is (= :heartbeat (-> network-out1 first :chunks first :type)) "Packet should be heartbeat")
          _ (is (some? (get-in new-state1 [:timers :sctp/t-heartbeat-rtx])) "Should start t-heartbeat-rtx timer")
          _ (is (= 0 (:heartbeat-error-count new-state1)) "Error count should remain 0 initially")

          ;; 2. Expire t-heartbeat-rtx
          rtx-expire-time (+ timer-expire-time 1000)
          res2 (core/handle-timeout new-state1 :sctp/t-heartbeat-rtx rtx-expire-time)
          new-state2 (:new-state res2)
          network-out2 (:network-out res2)
          app-events2 (:app-events res2)]

        ;; Since max-retransmissions is 0, the first error count increment (to 1) should exceed it and close the connection.
      (is (= :closed (:state new-state2)) "Connection should be closed")
      (is (= 1 (count network-out2)) "Should send an abort packet")
      (is (= :abort (-> network-out2 first :chunks first :type)) "Packet should be abort")
      (is (= 2 (count app-events2)) "Should generate an app event")
      (is (= :on-error (:type (first app-events2))) "App event should be on-error")
      (is (= :max-retransmissions (:cause (first app-events2))) "Cause should be max-retransmissions")
      (is (nil? (get-in new-state2 [:timers :sctp/t-heartbeat])) "Heartbeat timer should be removed")
      (is (nil? (get-in new-state2 [:timers :sctp/t-heartbeat-rtx])) "Heartbeat RTX timer should be removed"))))
