(ns datachannel.sctp-retransmission-metrics-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest retransmission-metrics-are-set-for-normal-retransmit-test
  (testing "Retransmission Metrics Are Set For Normal Retransmit"
    (let [now (System/currentTimeMillis)
          state-atom (atom {:state :established
                            :remote-ver-tag 1234
                            :local-ver-tag 5678
                            :next-tsn 1000
                            :ssn 0
                            :timers {}
                            :tx-queue []
                            :max-retransmissions 10
                            :metrics {:tx-packets 0
                                      :rx-packets 0
                                      :tx-bytes 0
                                      :rx-bytes 0
                                      :retransmissions 0
                                      :unacked-data 0}})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          conn {:state state-atom
                :sctp-out out-queue
                :on-error (atom (fn [cause] nil))}
          payload (.getBytes "test payload")]

      ;; Send data
      (core/send-data conn payload 0 :webrtc/string)

      (let [data-packet (.poll out-queue)]
        (is data-packet "Should send data packet")
        (is (= :data (:type (first (:chunks data-packet)))) "Chunk should be data")
        ;; Initially retransmissions should be 0
        (is (= 0 (:retransmissions (:metrics @state-atom))) "Initial retransmissions metric is 0"))

      ;; Expire T3-rtx timer once
      (let [timer-expire-time (+ now 1000)
            {:keys [new-state network-out]} (core/handle-timeout @state-atom :t3-rtx timer-expire-time)]
        (reset! state-atom new-state)
        (is (= :established (:state @state-atom)) "State should remain established")

        (let [rtx-effect (first network-out)]
          (is rtx-effect "Should emit an effect to retransmit data packet")
          (is (= :data (:type (first (:chunks rtx-effect)))) "Should retransmit data"))

        ;; Assert the metric increased
        (is (= 1 (:retransmissions (:metrics @state-atom))) "Retransmissions metric should be incremented to 1"))

      ;; Expire T3-rtx timer again
      (let [timer-expire-time-2 (+ now 1000 2000)
            {:keys [new-state network-out]} (core/handle-timeout @state-atom :t3-rtx timer-expire-time-2)]
        (reset! state-atom new-state)

        ;; Assert the metric increased again
        (is (= 2 (:retransmissions (:metrics @state-atom))) "Retransmissions metric should be incremented to 2")))))
