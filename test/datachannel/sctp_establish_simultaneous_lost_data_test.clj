(ns datachannel.sctp-establish-simultaneous-lost-data-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest establish-simultaneous-connection-with-lost-data-test
  (testing "Establish Simultaneous Connection With Lost Data"
    (let [state-a (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed})
          state-z (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed})
          out-a (java.util.concurrent.LinkedBlockingQueue.)
          out-z (java.util.concurrent.LinkedBlockingQueue.)
          conn-a {:state state-a :sctp-out out-a :on-open (atom nil) :on-message (atom nil) :on-data (atom nil) :selector nil}
          conn-z {:state state-z :sctp-out out-z :on-open (atom nil) :on-message (atom nil) :on-data (atom nil) :selector nil}
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
                                       nil)))))
          z-messages (atom [])]

      (reset! (:on-message conn-z) (fn [msg] (swap! z-messages conj msg)))

      ;; A starts connection: sends INIT
      (reset! state-a (merge @state-a {:state :cookie-wait :init-tag 1111}))
      (let [init-packet-a {:src-port 5000 :dst-port 5001 :verification-tag 0
                           :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                     :outbound-streams 1 :inbound-streams 1
                                     :initial-tsn 1000 :params {}}]}]

        ;; Z also starts connection: sends INIT (Simultaneous Connect)
        (reset! state-z (merge @state-z {:state :cookie-wait :init-tag 2222}))
        (let [init-packet-z {:src-port 5001 :dst-port 5000 :verification-tag 0
                             :chunks [{:type :init :init-tag 2222 :a-rwnd 100000
                                       :outbound-streams 1 :inbound-streams 1
                                       :initial-tsn 2000 :params {}}]}]

          ;; Queue data on A BEFORE it receives Z's INIT-ACK
          (core/send-data conn-a (.getBytes "hello" "UTF-8") 1 :webrtc/string)

          ;; Note: In Sans-IO, `send-data` buffers the packet in `tx-queue` because the connection is not yet established.
          ;; It will NOT be emitted to `out-a` right now.

          (let []
            ;; Setup collision:
            ;; A receives Z's INIT
            (handle-sctp-packet conn-a init-packet-z)
            ;; Z receives A's INIT
            (handle-sctp-packet conn-z init-packet-a)

            (let [init-ack-from-a (.poll out-a)
                  init-ack-from-z (.poll out-z)]
              (is init-ack-from-a "A should send INIT-ACK")
              (is init-ack-from-z "Z should send INIT-ACK")

              ;; Z receives A's INIT-ACK
              (handle-sctp-packet conn-z init-ack-from-a)
              ;; A receives Z's INIT-ACK
              (handle-sctp-packet conn-a init-ack-from-z)

              (let [cookie-echo-from-z (.poll out-z)
                    cookie-echo-from-a (.poll out-a)]
                (is cookie-echo-from-z "Z should send COOKIE-ECHO")
                (is cookie-echo-from-a "A should send COOKIE-ECHO")

                ;; Z receives A's COOKIE-ECHO
                (handle-sctp-packet conn-z cookie-echo-from-a)
                ;; A receives Z's COOKIE-ECHO
                (handle-sctp-packet conn-a cookie-echo-from-z)

                ;; When A receives Z's COOKIE-ECHO, it transitions to :established.
                ;; It should output COOKIE-ACK, and also flush its tx-queue (the DATA packet).
                (let [pkts-a (loop [acc []]
                               (if-let [p (.poll out-a)]
                                 (recur (conj acc p))
                                 acc))
                      cookie-ack-from-z (.poll out-z)
                      cookie-ack-from-a (first (filter #(= :cookie-ack (-> % :chunks first :type)) pkts-a))
                      data-packet-a     (first (filter #(= :data (-> % :chunks first :type)) pkts-a))]

                  (is cookie-ack-from-z "Z should send COOKIE-ACK")
                  (is cookie-ack-from-a "A should send COOKIE-ACK")
                  (is data-packet-a "A should queue DATA packet and send it after transition to :established")

                  ;; Both receive COOKIE-ACK
                  (handle-sctp-packet conn-z cookie-ack-from-a)
                  (handle-sctp-packet conn-a cookie-ack-from-z)

                  (is (= :established (:state @state-a)) "A should be ESTABLISHED")
                  (is (= :established (:state @state-z)) "Z should be ESTABLISHED")
                  (is (empty? @z-messages) "Z should not have received the message yet (packet lost)")

                  ;; Simulate timeout by A retransmitting the lost DATA packet
                  ;; Wait, instead of just feeding the extracted data-packet-a,
                  ;; let's trigger the T3-rtx timeout to retransmit it automatically!
                  (let [now-ms (+ (System/currentTimeMillis) 1500)
                        res (@#'core/handle-timeout @state-a :t3-rtx now-ms)]
                    (reset! state-a (:new-state res))
                    (doseq [out (:network-out res)] (.offer (:sctp-out conn-a) out))

                    (let [retransmitted-data-a (.poll out-a)]
                      (is retransmitted-data-a "A should retransmit DATA packet on T3 timeout")
                      (is (= :data (-> retransmitted-data-a :chunks first :type)))

                      ;; Z receives retransmitted DATA packet
                      (handle-sctp-packet conn-z retransmitted-data-a)

                      (is (= 1 (count @z-messages)) "Z should have received the message")
                      (is (= "hello" (if-let [msg (first @z-messages)] (String. ^bytes msg "UTF-8") nil)) "Message should be 'hello'"))))))))))))
