(ns datachannel.sctp-close-after-second-lost-heartbeat-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest close-connection-after-second-lost-heartbeat-test
  (testing "Close Connection After Second Lost Heartbeat"
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
                 :max-retransmissions 1} ;; Set to 1 so the second lost heartbeat closes it

          ;; 1. Expire t-heartbeat
          timer-expire-time (+ now 30000)
          res1 (core/handle-timeout state :sctp/t-heartbeat timer-expire-time)
          new-state1 (:new-state res1)
          network-out1 (:network-out res1)]
      (is (= 1 (count network-out1)) "Should send a heartbeat packet")
      (is (= :heartbeat (-> network-out1 first :chunks first :type)) "Packet should be heartbeat")
      (is (some? (get-in new-state1 [:timers :sctp/t-heartbeat-rtx])) "Should start t-heartbeat-rtx timer")
      (is (= 0 (:heartbeat-error-count new-state1)) "Error count should remain 0 initially")

        ;; 2. Expire t-heartbeat-rtx -> error count becomes 1, state should be established
      (let [rtx-expire-time (+ timer-expire-time 1000)
            res2 (core/handle-timeout new-state1 :sctp/t-heartbeat-rtx rtx-expire-time)
            new-state2 (:new-state res2)
            network-out2 (:network-out res2)
            app-events2 (:app-events res2)]

        (is (= 1 (:heartbeat-error-count new-state2)) "Error count should be 1")
        (is (= :established (:state new-state2)) "State should remain established")
        (is (= 0 (count network-out2)) "Should not send packet yet")
        (is (= 0 (count app-events2)) "Should not generate events yet")

          ;; 3. Expire t-heartbeat again
        (let [timer-expire-time2 (+ timer-expire-time 30000)
              res3 (core/handle-timeout new-state2 :sctp/t-heartbeat timer-expire-time2)
              new-state3 (:new-state res3)
              network-out3 (:network-out res3)]
          (is (= 1 (count network-out3)) "Should send another heartbeat packet")
          (is (= :heartbeat (-> network-out3 first :chunks first :type)) "Packet should be heartbeat")
          (is (some? (get-in new-state3 [:timers :sctp/t-heartbeat-rtx])) "Should restart t-heartbeat-rtx timer")
          (is (= 1 (:heartbeat-error-count new-state3)) "Error count should still be 1")

            ;; 4. Expire t-heartbeat-rtx again -> error count becomes 2, which is > 1
          (let [rtx-expire-time2 (+ timer-expire-time2 1000)
                res4 (core/handle-timeout new-state3 :sctp/t-heartbeat-rtx rtx-expire-time2)
                new-state4 (:new-state res4)
                network-out4 (:network-out res4)
                app-events4 (:app-events res4)]

              ;; Since max-retransmissions is 1, the second error count increment (to 2) should exceed it and close the connection.
            (is (= :closed (:state new-state4)) "Connection should be closed")
            (is (= 1 (count network-out4)) "Should send an abort packet")
            (is (= :abort (-> network-out4 first :chunks first :type)) "Packet should be abort")
            (is (= 1 (count app-events4)) "Should generate an app event")
            (is (= :on-error (:type (first app-events4))) "App event should be on-error")
            (is (= :max-retransmissions (:cause (first app-events4))) "Cause should be max-retransmissions")
            (is (nil? (get-in new-state4 [:timers :sctp/t-heartbeat])) "Heartbeat timer should be removed")
            (is (nil? (get-in new-state4 [:timers :sctp/t-heartbeat-rtx])) "Heartbeat RTX timer should be removed")))))))
