(ns datachannel.sctp-recovers-on-last-retransmission-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest recovers-on-last-retransmission-test
  (testing "Recover On Last Retransmission"
    (let [max-rtx 5
          state-atom (atom {:state :established
                            :remote-ver-tag 1234
                            :local-ver-tag 5678
                            :next-tsn 1000
                            :ssn 0
                            :timers {}
                            :tx-queue []
                            :max-retransmissions max-rtx})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          conn {:state state-atom
                :sctp-out out-queue
                :on-error (atom (fn [cause] nil))}
          payload (.getBytes "test payload")
          now (System/currentTimeMillis)]

      ;; 1. Send data
      (core/send-data conn payload 0 :webrtc/string)
      (let [data-packet (.poll out-queue)]
        (is data-packet "Should send data packet")
        (is (= :data (:type (first (:chunks data-packet)))) "Chunk should be data"))

      ;; 2. Simulate expiring T3-rtx up to (max-rtx - 1) times
      (loop [i 0
             current-time (+ now 1000)]
        (when (< i (dec max-rtx))
          (let [{:keys [new-state network-out app-events]} (core/handle-timeout @state-atom :t3-rtx current-time)]
            (reset! state-atom new-state)
            (is (= :established (:state @state-atom)) "State should remain established during retransmissions")
            (let [rtx-effect (first network-out)]
              (is rtx-effect "Should emit an effect to retransmit data packet")
              (is (= :data (:type (first (:chunks rtx-effect)))) "Should retransmit data"))
            (recur (inc i) (+ current-time (* 1000 (Math/pow 2 (inc i))))))))

      ;; 3. The next timeout would abort the connection.
      ;; Before it does, simulate receiving a SACK acknowledging the retransmitted packet.
      (let [sack-packet {:src-port 5000
                         :dst-port 5000
                         :verification-tag 5678
                         :chunks [{:type :sack
                                   :cum-tsn-ack 1000
                                   :a-rwnd 100000
                                   :gap-blocks []
                                   :duplicate-tsns []}]}
            {:keys [new-state]} (#'core/handle-sctp-packet @state-atom sack-packet now)]
        (reset! state-atom new-state)

        ;; 4. Check if the connection recovered
        (is (= :established (:state @state-atom)) "Connection should remain established")
        (is (empty? (:tx-queue @state-atom)) "TX queue should be empty after receiving SACK")
        (is (nil? (get-in @state-atom [:timers :t3-rtx])) "T3-rtx timer should be cleared")))))
