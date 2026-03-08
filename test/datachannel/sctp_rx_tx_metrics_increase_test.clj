(ns datachannel.sctp-rx-tx-metrics-increase-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest rx-and-tx-packet-metrics-increase-test
  (testing "Rx And Tx Packet Metrics Increase"
    (let [state-a (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed :initial-tsn 1000})
          state-z (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed :initial-tsn 2000})
          out-a (java.util.concurrent.LinkedBlockingQueue.)
          out-z (java.util.concurrent.LinkedBlockingQueue.)
          conn-a {:state state-a :sctp-out out-a   :selector nil}
          conn-z {:state state-z :sctp-out out-z   :selector nil}
          handle-sctp-packet (fn [p c]
                               (when (and p c)
                                 (let [state-map @(:state c)
                                       res (@#'core/handle-sctp-packet state-map p (System/currentTimeMillis))
                                       next-state (:new-state res)
                                       network-out (:network-out res)]
                                   (reset! (:state c) next-state)
                                   (doseq [p network-out] (.offer (:sctp-out c) p)))))]

      ;; A sends INIT
      (let [res (@#'core/handle-event @state-a {:type :connect} (System/currentTimeMillis))]
        (reset! state-a (:new-state res))
        (doseq [p (:network-out res)] (.offer out-a p)))

      (let [init-a (.poll out-a)]
        (handle-sctp-packet init-a conn-z))

      (let [init-ack-z (.poll out-z)]
        (handle-sctp-packet init-ack-z conn-a))

      (let [cookie-echo-a (.poll out-a)]
        (handle-sctp-packet cookie-echo-a conn-z))

      (let [cookie-ack-z (.poll out-z)]
        (handle-sctp-packet cookie-ack-z conn-a))

      ;; Connection established. Wait, init/connect adds +1 tx? We need to make sure we count tx for handle-event
      (is (= :established (:state @state-a)))
      (is (= :established (:state @state-z)))

      (let [metrics-a (:metrics @state-a)]
        ;; 1. INIT, 2. COOKIE_ECHO. Total 2 tx.
        ;; A receives 1. INIT_ACK, 2. COOKIE_ACK. Total 2 rx.
        (is (= 2 (:tx-packets metrics-a)))
        (is (= 2 (:rx-packets metrics-a)))
        (is (= 0 (get metrics-a :unacked-data 0))))

      (let [metrics-z (:metrics @state-z)]
        ;; 1. INIT_ACK, 2. COOKIE_ACK. Total 2 tx.
        ;; Z receives 1. INIT, 2. COOKIE_ECHO. Total 2 rx.
        (is (= 2 (:tx-packets metrics-z)))
        (is (= 2 (:rx-packets metrics-z))))

      ;; A sends data
      (core/send-data conn-a (byte-array 2) 1 :webrtc/string)

      (let [metrics-a (:metrics @state-a)]
        (is (= 1 (:unacked-data metrics-a)))
        ;; send-data places data packet into out-a immediately because it is established
        (is (= 3 (:tx-packets metrics-a))))

      ;; Z receives DATA
      (let [data-a (.poll out-a)]
        (handle-sctp-packet data-a conn-z))

      ;; A receives SACK
      (let [sack-z (.poll out-z)]
        (handle-sctp-packet sack-z conn-a))

      (let [metrics-a (:metrics @state-a)]
        (is (= 0 (get metrics-a :unacked-data 0)))
        (is (= 3 (:tx-packets metrics-a)))
        (is (= 3 (:rx-packets metrics-a))))

      (let [metrics-z (:metrics @state-z)]
        (is (= 3 (:tx-packets metrics-z)))
        (is (= 3 (:rx-packets metrics-z)))))))
