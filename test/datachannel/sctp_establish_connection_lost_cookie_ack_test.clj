(ns datachannel.sctp-establish-connection-lost-cookie-ack-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest establish-connection-lost-cookie-ack-test
  (testing "Establish Connection Lost Cookie Ack"
    (let [now 1000
          client-state {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed}
          server-state {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed}

          ;; 1. Client initiates connection
          client-state-cw (merge client-state {:state :cookie-wait :local-ver-tag 1111})
          init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                       :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                 :outbound-streams 1 :inbound-streams 1
                                 :initial-tsn 1000 :params {}}]}

          ;; Server receives INIT, sends INIT-ACK
          res-s1 (@#'core/handle-sctp-packet server-state init-packet now)
          server-state-ia (:new-state res-s1)
          init-ack-packet (first (:network-out res-s1))]
      (is init-ack-packet "Server should send INIT-ACK")

      ;; Client receives INIT-ACK, sends COOKIE-ECHO
      (let [res-c1 (@#'core/handle-sctp-packet client-state-cw init-ack-packet now)
            client-state-ce (:new-state res-c1)
            cookie-echo-packet (first (:network-out res-c1))]
        (is cookie-echo-packet "Client should send COOKIE-ECHO")

        ;; Server receives COOKIE-ECHO, sends COOKIE-ACK, enters :established
        (let [res-s2 (@#'core/handle-sctp-packet server-state-ia cookie-echo-packet now)
              server-state-est (:new-state res-s2)
              cookie-ack-packet (first (:network-out res-s2))]
          (is cookie-ack-packet "Server should send COOKIE-ACK")
          (is (= :established (:state server-state-est)) "Server should be in :established state")

          ;; The COOKIE-ACK is lost! It never reaches the client.
          ;; Client's t1-cookie timer expires.
          (let [time-2 (+ now 3000)
                res-c2 (core/handle-timeout client-state-ce :sctp/t1-cookie time-2)
                client-state-ce2 (:new-state res-c2)
                retry-cookie-echo-packet (first (:network-out res-c2))]
            (is retry-cookie-echo-packet "Client should resend COOKIE-ECHO on timeout")

            ;; Server receives duplicate COOKIE-ECHO
            (let [res-s3 (@#'core/handle-sctp-packet server-state-est retry-cookie-echo-packet time-2)
                  server-state-est2 (:new-state res-s3)
                  retry-cookie-ack-packet (first (:network-out res-s3))]
              (is retry-cookie-ack-packet "Server should send COOKIE-ACK in response to duplicate COOKIE-ECHO")
              (is (= :established (:state server-state-est2)) "Server should remain in :established state")

              ;; Client finally receives COOKIE-ACK
              (let [res-c3 (@#'core/handle-sctp-packet client-state-ce2 retry-cookie-ack-packet time-2)
                    client-state-est (:new-state res-c3)]
                (is (= :established (:state client-state-est)) "Client should enter :established state")))))))))
