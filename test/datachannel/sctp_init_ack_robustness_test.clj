(ns datachannel.sctp-init-ack-robustness-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest connection-can-continue-from-first-init-ack-test
  (testing "Connection Can Continue From First Init Ack"
    (let [now-ms 1000
          client-state0 {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :state :closed}
          server-state0 {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0 :state :closed}

          ;; 1. Client initiates connection with INIT
          client-state1 (assoc client-state0 :state :cookie-wait :init-tag (:local-ver-tag client-state0))
          init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                       :chunks [{:type :init
                                 :init-tag (:local-ver-tag client-state1)
                                 :a-rwnd 100000
                                 :outbound-streams 10
                                 :inbound-streams 10
                                 :initial-tsn (:next-tsn client-state1)
                                 :params {}}]}

          ;; Server receives INIT first time
          res-s1 (@#'core/handle-sctp-packet server-state0 init-packet now-ms)
          server-state1 (:new-state res-s1)
          init-ack-packet1 (first (:network-out res-s1))
          _ (is init-ack-packet1 "Server should produce first INIT-ACK")

          ;; Server receives the exact same INIT again (e.g. retransmission by client)
          res-s2 (@#'core/handle-sctp-packet server-state1 init-packet now-ms)
          server-state2 (:new-state res-s2)
          init-ack-packet2 (first (:network-out res-s2))
          _ (is init-ack-packet2 "Server should produce second INIT-ACK")

          ;; Verify that they are indeed distinct INIT-ACKs (cookie differs)
          chunk1 (first (:chunks init-ack-packet1))
          chunk2 (first (:chunks init-ack-packet2))
          _ (is (not= (:cookie (:params chunk1))
                      (:cookie (:params chunk2)))
                "Second INIT-ACK should have a different cookie than the first")

          ;; Client proceeds using the FIRST INIT-ACK
          res-c1 (@#'core/handle-sctp-packet client-state1 init-ack-packet1 now-ms)
          client-state2 (:new-state res-c1)
          cookie-echo-packet (first (:network-out res-c1))
          _ (is cookie-echo-packet "Client should produce COOKIE-ECHO in response to INIT-ACK1")

          ;; Server receives COOKIE-ECHO
          res-s3 (@#'core/handle-sctp-packet server-state2 cookie-echo-packet now-ms)
          server-state3 (:new-state res-s3)
          cookie-ack-packet (first (:network-out res-s3))
          _ (is cookie-ack-packet "Server should produce COOKIE-ACK in response to COOKIE-ECHO")

          ;; Verify server state transitioned properly
          _ (is (= :established (:state server-state3)) "Server should transition to established")
          _ (is (some #(= :on-open (:type %)) (:app-events res-s3)) "Server on-open should be called")

          ;; Client receives COOKIE-ACK
          res-c2 (@#'core/handle-sctp-packet client-state2 cookie-ack-packet now-ms)
          client-state3 (:new-state res-c2)]

      (is (= :established (:state client-state3)) "Client should transition to established")
      (is (some #(= :on-open (:type %)) (:app-events res-c2)) "Client on-open should be called"))))

(deftest connection-can-continue-from-second-init-ack-test
  (testing "Connection Can Continue From Second Init Ack"
    (let [now-ms 1000
          client-state0 {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :state :closed}
          server-state0 {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0 :state :closed}

          ;; 1. Client initiates connection with INIT
          client-state1 (assoc client-state0 :state :cookie-wait :init-tag (:local-ver-tag client-state0))
          init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                       :chunks [{:type :init
                                 :init-tag (:local-ver-tag client-state1)
                                 :a-rwnd 100000
                                 :outbound-streams 10
                                 :inbound-streams 10
                                 :initial-tsn (:next-tsn client-state1)
                                 :params {}}]}

          ;; Server receives INIT first time
          res-s1 (@#'core/handle-sctp-packet server-state0 init-packet now-ms)
          server-state1 (:new-state res-s1)
          init-ack-packet1 (first (:network-out res-s1))
          _ (is init-ack-packet1 "Server should produce first INIT-ACK")

          ;; Server receives the exact same INIT again (e.g. retransmission by client)
          res-s2 (@#'core/handle-sctp-packet server-state1 init-packet now-ms)
          server-state2 (:new-state res-s2)
          init-ack-packet2 (first (:network-out res-s2))
          _ (is init-ack-packet2 "Server should produce second INIT-ACK")

          ;; Verify that they are indeed distinct INIT-ACKs (cookie differs)
          chunk1 (first (:chunks init-ack-packet1))
          chunk2 (first (:chunks init-ack-packet2))
          _ (is (not= (:cookie (:params chunk1))
                      (:cookie (:params chunk2)))
                "Second INIT-ACK should have a different cookie than the first")

          ;; Client proceeds using the SECOND INIT-ACK
          res-c1 (@#'core/handle-sctp-packet client-state1 init-ack-packet2 now-ms)
          client-state2 (:new-state res-c1)
          cookie-echo-packet (first (:network-out res-c1))
          _ (is cookie-echo-packet "Client should produce COOKIE-ECHO in response to INIT-ACK2")

          ;; Server receives COOKIE-ECHO
          res-s3 (@#'core/handle-sctp-packet server-state2 cookie-echo-packet now-ms)
          server-state3 (:new-state res-s3)
          cookie-ack-packet (first (:network-out res-s3))
          _ (is cookie-ack-packet "Server should produce COOKIE-ACK in response to COOKIE-ECHO")

          ;; Verify server state transitioned properly
          _ (is (= :established (:state server-state3)) "Server should transition to established")
          _ (is (some #(= :on-open (:type %)) (:app-events res-s3)) "Server on-open should be called")

          ;; Client receives COOKIE-ACK
          res-c2 (@#'core/handle-sctp-packet client-state2 cookie-ack-packet now-ms)
          client-state3 (:new-state res-c2)]

      (is (= :established (:state client-state3)) "Client should transition to established")
      (is (some #(= :on-open (:type %)) (:app-events res-c2)) "Client on-open should be called"))))
