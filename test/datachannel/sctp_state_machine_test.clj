(ns datachannel.sctp-state-machine-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest establish-connection-with-setup-collision-test
  (testing "Establish Connection With Setup Collision"
    (let [state-a (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed})
          state-z (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed})
          out-a (java.util.concurrent.LinkedBlockingQueue.)
          out-z (java.util.concurrent.LinkedBlockingQueue.)
          conn-a {:state state-a :sctp-out out-a   :selector nil}
          conn-z {:state state-z :sctp-out out-z   :selector nil}
          handle-sctp-packet (fn [p c]
                               (when (and p c)
                                 (let [state-map @(:state c)
                                       res (@#'core/handle-sctp-packet state-map p (System/currentTimeMillis))
                                       next-state (:new-state res)
                                       network-out (:network-out res)
                                       app-events (:app-events res)]
                                   (reset! (:state c) next-state)
                                   (doseq [out network-out] (.offer (:sctp-out c) out))
                                   (doseq [evt app-events]
                                     (case (:type evt)
                                       :on-message (when-let [cb (:on-message c)] (when (and cb @cb) (@cb (:payload evt))))
                                       :on-data (when-let [cb (:on-data c)] (when (and cb @cb) (@cb (assoc evt :payload (:payload evt) :stream-id (:stream-id evt)))))
                                       :on-open (when-let [cb (:on-open c)] (when (and cb @cb) (@cb)))
                                       :on-error (when-let [cb (:on-error c)] (when (and cb @cb) (@cb (:causes evt))))
                                       :on-close (when-let [cb (:on-close c)] (when (and cb @cb) (@cb)))
                                       nil)))))]

      ;; Both start connection simultaneously
      (reset! state-a (merge @state-a {:state :cookie-wait :init-tag 1111}))
      (reset! state-z (merge @state-z {:state :cookie-wait :init-tag 2222}))

      (let [init-packet-a {:src-port 5000 :dst-port 5001 :verification-tag 0
                           :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                     :outbound-streams 1 :inbound-streams 1
                                     :initial-tsn 1000 :params {}}]}
            init-packet-z {:src-port 5001 :dst-port 5000 :verification-tag 0
                           :chunks [{:type :init :init-tag 2222 :a-rwnd 100000
                                     :outbound-streams 1 :inbound-streams 1
                                     :initial-tsn 2000 :params {}}]}]
        ;; A receives Z's INIT, sends INIT-ACK
        (handle-sctp-packet init-packet-z conn-a)
        ;; Z receives A's INIT, sends INIT-ACK
        (handle-sctp-packet init-packet-a conn-z))

      (let [init-ack-from-a (.poll out-a)
            init-ack-from-z (.poll out-z)]
        (is init-ack-from-a "A should send INIT-ACK in response to Z's INIT")
        (is init-ack-from-z "Z should send INIT-ACK in response to A's INIT")

        ;; Z receives A's INIT-ACK, sends COOKIE-ECHO
        (handle-sctp-packet init-ack-from-a conn-z)
        ;; A receives Z's INIT-ACK, sends COOKIE-ECHO
        (handle-sctp-packet init-ack-from-z conn-a))

      (let [cookie-echo-from-z (.poll out-z)
            cookie-echo-from-a (.poll out-a)]
        (is cookie-echo-from-z "Z should send COOKIE-ECHO")
        (is cookie-echo-from-a "A should send COOKIE-ECHO")

        ;; Z receives A's COOKIE-ECHO, sends COOKIE-ACK and establishes
        (handle-sctp-packet cookie-echo-from-a conn-z)
        ;; A receives Z's COOKIE-ECHO, sends COOKIE-ACK and establishes
        (handle-sctp-packet cookie-echo-from-z conn-a))

      (is (= :established (:state @state-a)) "A should be established")
      (is (= :established (:state @state-z)) "Z should be established")

      (let [cookie-ack-from-z (.poll out-z)
            cookie-ack-from-a (.poll out-a)]
        (is cookie-ack-from-z "Z should send COOKIE-ACK")
        (is cookie-ack-from-a "A should send COOKIE-ACK")

        ;; They can receive each other's COOKIE-ACK (silently ignored/processed)
        (handle-sctp-packet cookie-ack-from-a conn-z)
        (handle-sctp-packet cookie-ack-from-z conn-a))

      (is (= :established (:state @state-a)) "A should remain established")
      (is (= :established (:state @state-z)) "Z should remain established"))))

(deftest shutting-down-while-establishing-connection-test
  (testing "Shutting Down While Establishing Connection"
    (let [state-a (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed})
          state-z (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed})
          out-a (java.util.concurrent.LinkedBlockingQueue.)
          out-z (java.util.concurrent.LinkedBlockingQueue.)
          conn-a {:state state-a :sctp-out out-a   :selector nil}
          conn-z {:state state-z :sctp-out out-z   :selector nil}
          handle-sctp-packet (fn [p c]
                               (when (and p c)
                                 (let [state-map @(:state c)
                                       res (@#'core/handle-sctp-packet state-map p (System/currentTimeMillis))
                                       next-state (:new-state res)
                                       network-out (:network-out res)
                                       app-events (:app-events res)]
                                   (reset! (:state c) next-state)
                                   (doseq [out network-out] (.offer (:sctp-out c) out))
                                   (doseq [evt app-events]
                                     (case (:type evt)
                                       :on-message (when-let [cb (:on-message c)] (when (and cb @cb) (@cb (:payload evt))))
                                       :on-data (when-let [cb (:on-data c)] (when (and cb @cb) (@cb (assoc evt :payload (:payload evt) :stream-id (:stream-id evt)))))
                                       :on-open (when-let [cb (:on-open c)] (when (and cb @cb) (@cb)))
                                       :on-error (when-let [cb (:on-error c)] (when (and cb @cb) (@cb (:causes evt))))
                                       :on-close (when-let [cb (:on-close c)] (when (and cb @cb) (@cb)))
                                       nil)))))]

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
