(ns datachannel.sctp-shutdown-timer-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest shutdown-timer-expires-too-many-time-closes-connection-test
  (testing "Shutdown Timer Expires Too Many Time Closes Connection"
    (let [now 1000000
          state {:remote-tsn 0
                 :remote-ver-tag 2222
                 :next-tsn 1000
                 :ssn 0
                 :state :shutdown-pending
                 :timers {}}

          shutdown-packet {:src-port 5000 :dst-port 5001 :verification-tag 2222 :chunks [{:type :shutdown}]}
          state-sent (-> state
                         (assoc :state :shutdown-sent)
                         (assoc-in [:timers :t2-shutdown] {:expires-at (+ now 1000) :delay 1000 :retries 0 :packet shutdown-packet}))]

      (is (= :shutdown-sent (:state state-sent)))

      ;; Expire timer 8 times
      (let [state-after-8-expiries
            (loop [current-state state-sent
                   i 0]
              (if (< i 8)
                (let [timer (get-in current-state [:timers :t2-shutdown])
                      next-now (:expires-at timer)
                      {:keys [new-state network-out]} (core/handle-timeout current-state :t2-shutdown next-now)]
                  ;; Check if it sent the retransmitted SHUTDOWN chunk
                  (is (seq network-out) "Should output retransmitted packet")
                  (is (= :shutdown (:type (first (:chunks (first network-out))))))
                  (recur new-state (inc i)))
                current-state))]

        ;; 9th expiry should close
        (let [timer (get-in state-after-8-expiries [:timers :t2-shutdown])
              next-now (:expires-at timer)
              {:keys [new-state network-out app-events]} (core/handle-timeout state-after-8-expiries :t2-shutdown next-now)]

          (is (= :closed (:state new-state)) "Connection should be closed after 9th expiry")
          (is (not (contains? (:timers new-state) :t2-shutdown)) "t2-shutdown timer should be removed")

          ;; Check if it sent an ABORT chunk
          (let [abort-pkt (first network-out)]
            (is abort-pkt "Should output abort packet")
            (is (= :abort (:type (first (:chunks abort-pkt)))) "Packet should contain ABORT chunk"))

          ;; Check if it fired an on-error event
          (let [error-event (first (filter #(= :on-error (:type %)) app-events))]
            (is error-event "Should output on-error app event")
            (is (= :max-retransmissions (:cause error-event)) "Error cause should be :max-retransmissions")))))))
