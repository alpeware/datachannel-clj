(ns datachannel.sctp-recovers-after-successful-ack-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest recovers-after-a-successful-ack-test
  (testing "Recovers After A Successful Ack"
    (let [now (System/currentTimeMillis)
          max-rtx 10
          state-atom (atom {:state :established
                            :remote-ver-tag 1234
                            :local-ver-tag 5678
                            :next-tsn 1000
                            :ssn 0
                            :timers {:t-heartbeat {:expires-at (+ now 30000)}}
                            :heartbeat-interval 30000
                            :heartbeat-error-count 0
                            :rto-initial 1000
                            :max-retransmissions max-rtx})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          conn {:state state-atom
                :sctp-out out-queue
                :on-error (atom (fn [cause] nil))}]

      ;; Lose (max-retransmissions - 1) heartbeats
      (loop [i 0
             current-time (+ now 30000)]
        (when (< i (dec max-rtx))
          ;; Expire t-heartbeat
          (let [{:keys [new-state network-out app-events]} (core/handle-timeout @state-atom :t-heartbeat current-time)]
            (reset! state-atom new-state)
            (doseq [packet network-out]
              (.offer out-queue packet)))
          (.poll out-queue) ;; discard heartbeat request packet

          ;; Expire t-heartbeat-rtx (simulates lost heartbeat)
          (let [{:keys [new-state network-out app-events]} (core/handle-timeout @state-atom :t-heartbeat-rtx (+ current-time 1000))]
            (reset! state-atom new-state)
            (doseq [packet network-out]
              (.offer out-queue packet))
            (is (= :established (:state @state-atom)) "State should remain established"))
          (recur (inc i) (+ current-time 30000))))

      ;; 10th time - last heartbeat before aborting
      (let [current-time (+ now (* 30000 max-rtx))]
        (let [{:keys [new-state network-out app-events]} (core/handle-timeout @state-atom :t-heartbeat current-time)]
          (reset! state-atom new-state)
          (doseq [packet network-out]
            (.offer out-queue packet))

          (let [hb-effect (first network-out)]
            (is hb-effect "Should emit an effect to send heartbeat")

            (let [packet (.poll out-queue)
                  chunk (first (:chunks packet))]
              (is packet)
              (is (= :heartbeat (:type chunk)))

              ;; Simulate receiving HEARTBEAT-ACK for this last heartbeat
              (let [ack-packet {:src-port 5000
                                :dst-port 5000
                                :verification-tag 5678
                                :chunks [{:type :heartbeat-ack
                                          :params (:params chunk)}]}
                    res (core/handle-sctp-packet @state-atom ack-packet current-time)]
                (reset! state-atom (:new-state res))))))

        ;; The heartbeat error count should be reset to 0, and t-heartbeat-rtx cleared
        (is (= 0 (:heartbeat-error-count @state-atom)))
        (is (not (contains? (:timers @state-atom) :t-heartbeat-rtx)))

        ;; Fast forward to next heartbeat to ensure it works again
        (let [next-time (+ current-time 30000)
              {:keys [new-state network-out]} (core/handle-timeout @state-atom :t-heartbeat next-time)]
          (reset! state-atom new-state)
          (doseq [packet network-out]
            (.offer out-queue packet))
          (let [packet (.poll out-queue)]
            (is packet)
            (is (= :heartbeat (:type (first (:chunks packet)))))))))))
