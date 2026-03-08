(ns datachannel.sctp-reconnect-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest one-peer-reconnects-test
  (testing "One Peer Reconnects"
    (let [out-a (java.util.concurrent.LinkedBlockingQueue.)
          state-a (atom {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :state :closed})
          conn-a {:sctp-out out-a :state state-a  :selector nil }

          out-z (java.util.concurrent.LinkedBlockingQueue.)
          state-z (atom {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0 :state :closed})
          conn-z {:sctp-out out-z :state state-z  :selector nil }

          handle-sctp-packet (fn [c p]
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

      ;; 1. Establish connection between A and Z
      (reset! state-a (merge @state-a {:state :cookie-wait :init-tag 1111}))
      (let [init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                         :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                   :outbound-streams 10 :inbound-streams 10
                                   :initial-tsn 100 :params {}}]}]
        (handle-sctp-packet conn-z init-packet))

      (let [init-ack (.poll out-z)]
        (handle-sctp-packet conn-a init-ack))

      (let [cookie-echo (.poll out-a)]
        (handle-sctp-packet conn-z cookie-echo))

      (let [cookie-ack (.poll out-z)]
        (handle-sctp-packet conn-a cookie-ack))

      (is (= :established (:state @state-a)) "A should be established")
      (is (= :established (:state @state-z)) "Z should be established")

      ;; 2. A "reconnects" - starts a new connection attempt from scratch
      ;; This simulates A crashing or restarting and trying to connect to Z again.
      (let [out-a2 (java.util.concurrent.LinkedBlockingQueue.)
            state-a2 (atom {:remote-ver-tag 0 :local-ver-tag 3333 :next-tsn 300 :ssn 0 :state :cookie-wait :init-tag 3333})
            conn-a2 {:sctp-out out-a2 :state state-a2  :selector nil }]

        ;; A2 sends a new INIT to Z
        (let [init-packet2 {:src-port 5000 :dst-port 5001 :verification-tag 0
                            :chunks [{:type :init :init-tag 3333 :a-rwnd 100000
                                      :outbound-streams 10 :inbound-streams 10
                                      :initial-tsn 300 :params {}}]}]
          (handle-sctp-packet conn-z init-packet2))

        ;; Z should reply with INIT-ACK, despite being established
        (let [init-ack2 (.poll out-z)]
          (is init-ack2 "Z should reply with INIT-ACK to the new INIT")
          (is (= :init-ack (-> init-ack2 :chunks first :type)))
          (handle-sctp-packet conn-a2 (assoc init-ack2 :src-port 5001 :dst-port 5000)))

        ;; A2 sends COOKIE-ECHO
        (let [cookie-echo2 (.poll out-a2)]
          (is cookie-echo2 "A2 should send COOKIE-ECHO")
          (is (= :cookie-echo (-> cookie-echo2 :chunks first :type)))
          (handle-sctp-packet conn-z (assoc cookie-echo2 :src-port 5000 :dst-port 5001)))

        ;; Z should reply with COOKIE-ACK and reset its state
        (let [cookie-ack2 (.poll out-z)]
          (is cookie-ack2 "Z should reply with COOKIE-ACK to the new COOKIE-ECHO")
          (is (= :cookie-ack (-> cookie-ack2 :chunks first :type)))
          (handle-sctp-packet conn-a2 (assoc cookie-ack2 :src-port 5001 :dst-port 5000)))

        (is (= :established (:state @state-a2)) "A2 should be established")
        (is (= :established (:state @state-z)) "Z should remain established")
        (is (= 3333 (:remote-ver-tag @state-z)) "Z should have updated its remote verification tag to A2's tag")))))
