(ns datachannel.sctp-state-machine-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest shutting-down-while-establishing-connection-test
  (testing "Shutting Down While Establishing Connection"
    (let [state-a (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed})
          state-z (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed})
          out-a (java.util.concurrent.LinkedBlockingQueue.)
          out-z (java.util.concurrent.LinkedBlockingQueue.)
          conn-a {:state state-a :sctp-out out-a :on-open (atom nil) :on-close (atom nil) :selector nil}
          conn-z {:state state-z :sctp-out out-z :on-open (atom nil) :on-close (atom nil) :selector nil}
          handle-sctp-packet #'core/handle-sctp-packet]

      ;; A starts connection: sends INIT
      (reset! state-a (merge @state-a {:state :cookie-wait :init-tag 1111}))
      (let [init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                         :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                   :outbound-streams 1 :inbound-streams 1
                                   :initial-tsn 1000 :params {}}]}]
        ;; Z receives INIT and replies with INIT-ACK
        (handle-sctp-packet init-packet conn-z))

      (let [init-ack-packet (.poll out-z)]
        (is init-ack-packet "Z should send INIT-ACK")
        ;; A receives INIT-ACK and replies with COOKIE-ECHO
        (handle-sctp-packet init-ack-packet conn-a))

      (let [cookie-echo-packet (.poll out-a)]
        (is cookie-echo-packet "A should send COOKIE-ECHO")

        ;; Before Z receives COOKIE-ECHO, A initiates SHUTDOWN locally
        (reset! state-a (assoc @state-a :state :shutdown-pending))

        ;; Z receives COOKIE-ECHO and replies with COOKIE-ACK
        (handle-sctp-packet cookie-echo-packet conn-z))

      ;; Z is now established
      (is (= :established (:state @state-z)) "Z should be established")

      (let [cookie-ack-packet (.poll out-z)]
        (is cookie-ack-packet "Z should send COOKIE-ACK")
        ;; A receives COOKIE-ACK. Because it's in :shutdown-pending, it transitions to :shutdown-sent and sends SHUTDOWN
        (handle-sctp-packet cookie-ack-packet conn-a))

      (is (= :shutdown-sent (:state @state-a)) "A should be in shutdown-sent state")

      (let [shutdown-packet (.poll out-a)]
        (is shutdown-packet "A should send SHUTDOWN")
        (is (= :shutdown (:type (first (:chunks shutdown-packet)))) "Packet should contain SHUTDOWN chunk")
        ;; Z receives SHUTDOWN and replies with SHUTDOWN-ACK
        (handle-sctp-packet shutdown-packet conn-z))

      (is (= :shutdown-ack-sent (:state @state-z)) "Z should be in shutdown-ack-sent state")

      (let [shutdown-ack-packet (.poll out-z)]
        (is shutdown-ack-packet "Z should send SHUTDOWN-ACK")
        (is (= :shutdown-ack (:type (first (:chunks shutdown-ack-packet)))) "Packet should contain SHUTDOWN-ACK chunk")
        ;; A receives SHUTDOWN-ACK and replies with SHUTDOWN-COMPLETE
        (handle-sctp-packet shutdown-ack-packet conn-a))

      (is (= :closed (:state @state-a)) "A should be in closed state")

      (let [shutdown-complete-packet (.poll out-a)]
        (is shutdown-complete-packet "A should send SHUTDOWN-COMPLETE")
        (is (= :shutdown-complete (:type (first (:chunks shutdown-complete-packet)))) "Packet should contain SHUTDOWN-COMPLETE chunk")
        ;; Z receives SHUTDOWN-COMPLETE
        (handle-sctp-packet shutdown-complete-packet conn-z))

      (is (= :closed (:state @state-z)) "Z should be in closed state"))))
