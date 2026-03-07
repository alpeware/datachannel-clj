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
          handle-sctp-packet #'core/handle-sctp-packet
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
          (let [data-packet-a (.poll out-a)]
            (is data-packet-a "A should queue DATA packet")
            (is (= :data (-> data-packet-a :chunks first :type)))

            ;; Setup collision:
            ;; A receives Z's INIT
            (handle-sctp-packet init-packet-z conn-a)
            ;; Z receives A's INIT
            (handle-sctp-packet init-packet-a conn-z)

            (let [init-ack-from-a (.poll out-a)
                  init-ack-from-z (.poll out-z)]
              (is init-ack-from-a "A should send INIT-ACK")
              (is init-ack-from-z "Z should send INIT-ACK")

              ;; Z receives A's INIT-ACK
              (handle-sctp-packet init-ack-from-a conn-z)
              ;; A receives Z's INIT-ACK
              (handle-sctp-packet init-ack-from-z conn-a)

              (let [cookie-echo-from-z (.poll out-z)
                    cookie-echo-from-a (.poll out-a)]
                (is cookie-echo-from-z "Z should send COOKIE-ECHO")
                (is cookie-echo-from-a "A should send COOKIE-ECHO")

                ;; We drop the DATA packet that A previously queued (data-packet-a)
                ;; Z receives A's COOKIE-ECHO
                (handle-sctp-packet cookie-echo-from-a conn-z)
                ;; A receives Z's COOKIE-ECHO
                (handle-sctp-packet cookie-echo-from-z conn-a)

                (let [cookie-ack-from-z (.poll out-z)
                      cookie-ack-from-a (.poll out-a)]
                  (is cookie-ack-from-z "Z should send COOKIE-ACK")
                  (is cookie-ack-from-a "A should send COOKIE-ACK")

                  ;; Both receive COOKIE-ACK
                  (handle-sctp-packet cookie-ack-from-a conn-z)
                  (handle-sctp-packet cookie-ack-from-z conn-a)

                  (is (= :established (:state @state-a)) "A should be ESTABLISHED")
                  (is (= :established (:state @state-z)) "Z should be ESTABLISHED")
                  (is (empty? @z-messages) "Z should not have received the message yet (packet lost)")

                  ;; Simulate timeout by A retransmitting the lost DATA packet
                  (handle-sctp-packet data-packet-a conn-z)

                  (is (= 1 (count @z-messages)) "Z should have received the message")
                  (is (= "hello" (String. ^bytes (first @z-messages) "UTF-8")) "Message should be 'hello'"))))))))))
