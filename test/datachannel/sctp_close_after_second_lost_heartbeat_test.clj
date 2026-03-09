(ns datachannel.sctp-close-after-second-lost-heartbeat-test
  (:require [clojure.test :refer :all]
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
          ]

      ;; 1. Expire t-heartbeat
      (let [timer-expire-time (+ now 30000)
            {:keys [new-state network-out app-events]} (core/handle-timeout state :sctp/t-heartbeat timer-expire-time)]
        (is (= 1 (count network-out)) "Should send a heartbeat packet")
        (is (= :heartbeat (-> network-out first :chunks first :type)) "Packet should be heartbeat")
        (is (some? (get-in new-state [:timers :sctp/t-heartbeat-rtx])) "Should start t-heartbeat-rtx timer")
        (is (= 0 (:heartbeat-error-count new-state)) "Error count should remain 0 initially")

        ;; 2. Expire t-heartbeat-rtx -> error count becomes 1, state should be established
        (let [rtx-expire-time (+ timer-expire-time 1000)
              {:keys [new-state network-out app-events]} (core/handle-timeout new-state :sctp/t-heartbeat-rtx rtx-expire-time)]

          (is (= 1 (:heartbeat-error-count new-state)) "Error count should be 1")
          (is (= :established (:state new-state)) "State should remain established")
          (is (= 0 (count network-out)) "Should not send packet yet")
          (is (= 0 (count app-events)) "Should not generate events yet")

          ;; 3. Expire t-heartbeat again
          (let [timer-expire-time2 (+ timer-expire-time 30000)
                {:keys [new-state network-out app-events]} (core/handle-timeout new-state :sctp/t-heartbeat timer-expire-time2)]
            (is (= 1 (count network-out)) "Should send another heartbeat packet")
            (is (= :heartbeat (-> network-out first :chunks first :type)) "Packet should be heartbeat")
            (is (some? (get-in new-state [:timers :sctp/t-heartbeat-rtx])) "Should restart t-heartbeat-rtx timer")
            (is (= 1 (:heartbeat-error-count new-state)) "Error count should still be 1")

            ;; 4. Expire t-heartbeat-rtx again -> error count becomes 2, which is > 1
            (let [rtx-expire-time2 (+ timer-expire-time2 1000)
                  {:keys [new-state network-out app-events]} (core/handle-timeout new-state :sctp/t-heartbeat-rtx rtx-expire-time2)]

              ;; Since max-retransmissions is 1, the second error count increment (to 2) should exceed it and close the connection.
              (is (= :closed (:state new-state)) "Connection should be closed")
              (is (= 1 (count network-out)) "Should send an abort packet")
              (is (= :abort (-> network-out first :chunks first :type)) "Packet should be abort")
              (is (= 1 (count app-events)) "Should generate an app event")
              (is (= :on-error (:type (first app-events))) "App event should be on-error")
              (is (= :max-retransmissions (:cause (first app-events))) "Cause should be max-retransmissions")
              (is (nil? (get-in new-state [:timers :sctp/t-heartbeat])) "Heartbeat timer should be removed")
              (is (nil? (get-in new-state [:timers :sctp/t-heartbeat-rtx])) "Heartbeat RTX timer should be removed"))))))))
