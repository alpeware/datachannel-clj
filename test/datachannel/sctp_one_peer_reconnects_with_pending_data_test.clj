(ns datachannel.sctp-one-peer-reconnects-with-pending-data-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest one-peer-reconnects-with-pending-data-test
  (testing "One Peer Reconnects With Pending Data"
    (let [out-a (java.util.concurrent.LinkedBlockingQueue.)
          state-a (atom {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :state :closed})
          conn-a {:sctp-out out-a :state state-a :on-open (atom nil) :selector nil :on-close (atom nil)}

          out-z (java.util.concurrent.LinkedBlockingQueue.)
          state-z (atom {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0 :state :closed})
          conn-z {:sctp-out out-z :state state-z :on-open (atom nil) :selector nil :on-close (atom nil)}

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

      ;; A sends pending data to Z
      (core/send-data conn-a (.getBytes "hello" "UTF-8") 1 :webrtc/string)
      (let [data-packet-1 (.poll out-a)]
        (is data-packet-1 "A should queue DATA packet")
        ;; Z receives the data packet
        (handle-sctp-packet conn-z data-packet-1)
        )

      ;; Now Z reconnects (Z2)
      (let [out-z2 (java.util.concurrent.LinkedBlockingQueue.)
            state-z2 (atom {:remote-ver-tag 0 :local-ver-tag 3333 :next-tsn 300 :ssn 0 :state :cookie-wait :init-tag 3333})
            conn-z2 {:sctp-out out-z2 :state state-z2 :on-open (atom nil) :selector nil :on-close (atom nil) :on-message (atom nil)}
            z2-messages (atom [])]

        (reset! (:on-message conn-z2) (fn [msg] (swap! z2-messages conj msg)))

        ;; Z2 sends INIT
        (let [init-packet2 {:src-port 5001 :dst-port 5000 :verification-tag 0
                            :chunks [{:type :init :init-tag 3333 :a-rwnd 100000
                                      :outbound-streams 10 :inbound-streams 10
                                      :initial-tsn 300 :params {}}]}]
          (handle-sctp-packet conn-a init-packet2))

        ;; A replies with INIT-ACK
        (let [init-ack2 (.poll out-a)]
          (is init-ack2 "A should reply with INIT-ACK")
          (handle-sctp-packet conn-z2 init-ack2))

        ;; Z2 sends COOKIE-ECHO
        (let [cookie-echo2 (.poll out-z2)]
          (is cookie-echo2 "Z2 should send COOKIE-ECHO")
          (handle-sctp-packet conn-a cookie-echo2))

        ;; A replies with COOKIE-ACK
        (let [cookie-ack2 (.poll out-a)]
          (is cookie-ack2 "A should reply with COOKIE-ACK")
          (handle-sctp-packet conn-z2 cookie-ack2))

        (is (= :established (:state @state-z2)) "Z2 should be established")
        (is (= :established (:state @state-a)) "A should remain established")
        (is (= 3333 (:remote-ver-tag @state-a)) "A should have updated its remote verification tag to Z2's tag")

        ;; We simulate retransmitting the unacked data from A to Z2.
        ;; First we need A to send some data while we test. Z actually acked the packet but A never saw the ack because the reconnect happened
        ;; Actually wait, Z received the data packet but we didn't send SACK from Z to A?
        ;; let's check
        (let [sack-from-z (.poll out-z)]
            ;; Z sent sack. We DROP it to simulate packet loss / disconnect.
        )

        ;; Trigger T3 timeout on A.
        (let [res (@#'core/handle-timeout @state-a :t3-rtx (+ (System/currentTimeMillis) 2000))]
          (reset! state-a (:new-state res))
          (doseq [out (:network-out res)] (.offer out-a out)))

        (let [retransmitted-data (.poll out-a)]
          (is retransmitted-data "A should retransmit data")
          ;; Since A updated remote-ver-tag, this retransmitted data should have verification tag 3333
          (is (= 3333 (:verification-tag retransmitted-data)) "Retransmitted data should have Z2's verification tag")
          (handle-sctp-packet conn-z2 retransmitted-data))

        (is (= 1 (count @z2-messages)) "Z2 should receive the message")
        (is (= "hello" (if-let [msg (first @z2-messages)] (String. ^bytes msg "UTF-8") nil)) "Message should be 'hello'")
        ))))
