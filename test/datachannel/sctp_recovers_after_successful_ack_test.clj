(ns datachannel.sctp-recovers-after-successful-ack-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest recovers-after-a-successful-ack-test
  (testing "Recovers After A Successful Ack"
    (let [now (System/currentTimeMillis)
          max-rtx 10
          conn-state {:state :established
                      :remote-ver-tag 1234
                      :local-ver-tag 5678
                      :next-tsn 1000
                      :ssn 0
                      :timers {:sctp/t-heartbeat {:expires-at (+ now 30000)}}
                      :heartbeat-interval 30000
                      :heartbeat-error-count 0
                      :rto-initial 1000
                      :max-retransmissions max-rtx}

          ;; Lose (max-retransmissions - 1) heartbeats
          s-lost
          (loop [i 0
                 current-time (+ now 30000)
                 state conn-state]
            (if (< i (dec max-rtx))
              ;; Expire t-heartbeat
              (let [res1 (core/handle-timeout state :sctp/t-heartbeat current-time)
                    state1 (:new-state res1)
                    ;; Expire t-heartbeat-rtx (simulates lost heartbeat)
                    res2 (core/handle-timeout state1 :sctp/t-heartbeat-rtx (+ current-time 1000))
                    state2 (:new-state res2)]
                (is (= :established (:state state2)) "State should remain established")
                (recur (inc i) (+ current-time 30000) state2))
              state))

          ;; 10th time - last heartbeat before aborting
          current-time (+ now (* 30000 max-rtx))
          res-last (core/handle-timeout s-lost :sctp/t-heartbeat current-time)
          s-last (:new-state res-last)
          network-out-last (:network-out res-last)
          _ (is (not-empty network-out-last) "Should emit an effect to send heartbeat")
          hb-packet (first network-out-last)
          hb-chunk (first (:chunks hb-packet))
          _ (is (= :heartbeat (:type hb-chunk)))

          ;; Simulate receiving HEARTBEAT-ACK for this last heartbeat
          ack-packet {:src-port 5000
                      :dst-port 5000
                      :verification-tag 5678
                      :chunks [{:type :heartbeat-ack
                                :params (:params hb-chunk)}]}
          res-ack (core/handle-sctp-packet s-last ack-packet current-time)
          s-ack (:new-state res-ack)]

      ;; The heartbeat error count should be reset to 0, and t-heartbeat-rtx cleared
      (is (= 0 (:heartbeat-error-count s-ack)))
      (is (not (contains? (:timers s-ack) :sctp/t-heartbeat-rtx)))

      ;; Fast forward to next heartbeat to ensure it works again
      (let [next-time (+ current-time 30000)
            res-next (core/handle-timeout s-ack :sctp/t-heartbeat next-time)]
        (is (not-empty (:network-out res-next)))
        (is (= :heartbeat (:type (first (:chunks (first (:network-out res-next)))))))))))
