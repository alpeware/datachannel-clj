(ns datachannel.sctp-error-counter-reset-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest error-counter-is-reset-on-heartbeat-ack-test
  (testing "Error Counter Is Reset On Heartbeat Ack"
    (let [now (System/currentTimeMillis)
          state-atom (atom {:state :established
                            :remote-ver-tag 1234
                            :local-ver-tag 5678
                            :next-tsn 1000
                            :ssn 0
                            :timers {:sctp/t-heartbeat {:expires-at (+ now 30000)}}
                            :heartbeat-interval 30000
                            :heartbeat-error-count 0
                            :rto-initial 1000
                            :max-retransmissions 10})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          conn {:state state-atom
                :sctp-out out-queue
                :on-error (atom (fn [cause] nil))}]

      ;; 1. Expire t-heartbeat
      (let [timer-expire-time (+ now 30000)
            {:keys [new-state network-out]} (core/handle-timeout @state-atom :sctp/t-heartbeat timer-expire-time)]
        (reset! state-atom new-state))

      ;; 2. Expire t-heartbeat-rtx -> error count becomes 1
      (let [rtx-expire-time (+ now 30000 1000)
            {:keys [new-state network-out]} (core/handle-timeout @state-atom :sctp/t-heartbeat-rtx rtx-expire-time)]
        (reset! state-atom new-state)
        (is (= 1 (:heartbeat-error-count @state-atom))))

      ;; 3. Ack the next heartbeat
      (let [next-hb-time (+ now 60000)
            {:keys [new-state network-out]} (core/handle-timeout @state-atom :sctp/t-heartbeat next-hb-time)]
        (reset! state-atom new-state)
        (let [packet (first network-out)
              chunk (first (:chunks packet))]
          ;; Send ack
          (let [{:keys [new-state]} (#'core/handle-sctp-packet @state-atom
                                                                {:src-port 5000
                                                                 :dst-port 5000
                                                                 :verification-tag 5678
                                                                 :chunks [{:type :heartbeat-ack
                                                                           :params (:params chunk)}]}
                                                                next-hb-time)]
            (reset! state-atom new-state))))

      (is (= 0 (:heartbeat-error-count @state-atom))))))

(deftest error-counter-is-reset-on-data-ack-test
  (testing "Error Counter Is Reset On Data Ack"
    (let [now (System/currentTimeMillis)
          state-atom (atom {:state :established
                            :remote-ver-tag 1234
                            :local-ver-tag 5678
                            :next-tsn 1000
                            :ssn 0
                            :timers {}
                            :heartbeat-interval 30000
                            :heartbeat-error-count 0
                            :rto-initial 1000
                            :max-retransmissions 10})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          conn {:state state-atom
                :sctp-out out-queue
                :on-error (atom (fn [cause] nil))}]

      ;; Cause an error first
      (reset! state-atom (assoc @state-atom :heartbeat-error-count 1))

      (let [{:keys [new-state]} (#'core/handle-sctp-packet @state-atom
                                                            {:src-port 5000
                                                             :dst-port 5000
                                                             :verification-tag 5678
                                                             :chunks [{:type :sack
                                                                       :cum-tsn-ack 1000
                                                                       :a-rwnd 100000
                                                                       :gap-blocks []
                                                                       :duplicate-tsns []}]}
                                                            now)]
        (reset! state-atom new-state))

      (is (= 0 (:heartbeat-error-count @state-atom))))))
