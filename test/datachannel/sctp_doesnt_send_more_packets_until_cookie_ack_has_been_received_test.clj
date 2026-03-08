(ns datachannel.sctp-doesnt-send-more-packets-until-cookie-ack-has-been-received-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest doesnt-send-more-packets-until-cookie-ack-has-been-received-test
  (testing "Doesn't Send More Packets Until Cookie Ack Has Been Received"
    (let [now 1000
          client-state {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed}
          server-state {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed}

          ;; 1. Client initiates connection
          client-state-cw (merge client-state {:state :cookie-wait :local-ver-tag 1111})
          init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                       :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                 :outbound-streams 1 :inbound-streams 1
                                 :initial-tsn 1000 :params {}}]}

          ;; Client sends data while in :cookie-wait
          res-sd1 (core/send-data client-state-cw (.getBytes "Early Data 1" "UTF-8") 0 :webrtc/string now)
          client-state-cw1 (:new-state res-sd1)]

      (is (empty? (:network-out res-sd1)) "Client should not send the data packet yet")
      (is (= 1 (count (:tx-queue client-state-cw1))) "Data should be buffered in tx-queue")

      ;; Server receives INIT, sends INIT-ACK
      (let [res-s1 (@#'core/handle-sctp-packet server-state init-packet now)
            server-state-ia (:new-state res-s1)
            init-ack-packet (first (:network-out res-s1))]
        (is init-ack-packet "Server should send INIT-ACK")

        ;; Client receives INIT-ACK, sends COOKIE-ECHO
        (let [res-c1 (@#'core/handle-sctp-packet client-state-cw1 init-ack-packet now)
              client-state-ce (:new-state res-c1)
              cookie-echo-packet (first (:network-out res-c1))]
          (is cookie-echo-packet "Client should send COOKIE-ECHO")

          ;; Client sends more data while in :cookie-echoed
          (let [res-sd2 (core/send-data client-state-ce (.getBytes "Early Data 2" "UTF-8") 0 :webrtc/string now)
                client-state-ce2 (:new-state res-sd2)]

            (is (empty? (:network-out res-sd2)) "Client should not send data until COOKIE-ACK")
            (is (= 2 (count (:tx-queue client-state-ce2))) "Data should be buffered in tx-queue")

            ;; Server receives COOKIE-ECHO, sends COOKIE-ACK
            (let [res-s2 (@#'core/handle-sctp-packet server-state-ia cookie-echo-packet now)
                  server-state-ca (:new-state res-s2)
                  cookie-ack-packet (first (:network-out res-s2))]
              (is cookie-ack-packet "Server should send COOKIE-ACK")

              ;; Client receives COOKIE-ACK, becomes established, and SHOULD NOW send buffered data
              (let [res-c2 (@#'core/handle-sctp-packet client-state-ce2 cookie-ack-packet now)
                    client-state-est (:new-state res-c2)
                    out-pkts (:network-out res-c2)]

                (is (= :established (:state client-state-est)) "Client should be established")
                (is (= 2 (count out-pkts)) "Client should have sent 2 data packets upon receiving COOKIE-ACK")

                (let [p1 (first out-pkts)
                      p2 (second out-pkts)]
                  (is (= :data (:type (first (:chunks p1)))) "First packet should be DATA")
                  (is (= :data (:type (first (:chunks p2)))) "Second packet should be DATA")

                  (is (= "Early Data 1" (String. ^bytes (:payload (first (:chunks p1))) "UTF-8")))
                  (is (= "Early Data 2" (String. ^bytes (:payload (first (:chunks p2))) "UTF-8"))))))))))))
