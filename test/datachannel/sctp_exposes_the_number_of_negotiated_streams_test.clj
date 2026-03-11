(ns datachannel.sctp-exposes-the-number-of-negotiated-streams-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as dc]))

(deftest exposes-the-number-of-negotiated-streams-test
  (testing "Exposes The Number Of Negotiated Streams"
    (let [now-ms 1000
          opts-a {:local-outbound-streams 50 :local-inbound-streams 100}
          opts-z {:local-outbound-streams 200 :local-inbound-streams 10}
          state-a (@#'dc/create-connection opts-a true)
          state-z (@#'dc/create-connection opts-z false)

          ;; A connects: sends INIT
          res-a1 (dc/handle-event state-a {:type :connect} now-ms)
          state-a1 (:new-state res-a1)
          init-packet (first (:network-out res-a1))

          ;; Z receives INIT and replies with INIT-ACK
          res-z1 (@#'dc/handle-sctp-packet state-z init-packet now-ms)
          state-z1 (:new-state res-z1)
          init-ack-packet (first (:network-out res-z1))

          ;; A receives INIT-ACK and replies with COOKIE-ECHO
          res-a2 (@#'dc/handle-sctp-packet state-a1 init-ack-packet now-ms)
          state-a2 (:new-state res-a2)
          cookie-echo-packet (first (:network-out res-a2))

          ;; Z receives COOKIE-ECHO and replies with COOKIE-ACK
          res-z2 (@#'dc/handle-sctp-packet state-z1 cookie-echo-packet now-ms)
          state-z2 (:new-state res-z2)
          cookie-ack-packet (first (:network-out res-z2))

          ;; A receives COOKIE-ACK
          res-a3 (@#'dc/handle-sctp-packet state-a2 cookie-ack-packet now-ms)
          state-a3 (:new-state res-a3)]

      ;; Now both A and Z are in the :established state.
      (is (= (:state state-a3) :established))
      (is (= (:state state-z2) :established))

      ;; A's outbound limit is 50. Z's inbound limit is 10.
      ;; So negotiated outbound for A is min(50, 10) = 10.
      ;; A's inbound limit is 100. Z's outbound limit is 200.
      ;; So negotiated inbound for A is min(100, 200) = 100.
      (is (= 10 (:negotiated-outbound-streams state-a3)))
      (is (= 100 (:negotiated-inbound-streams state-a3)))

      ;; Z's outbound limit is 200. A's inbound limit is 100.
      ;; So negotiated outbound for Z is min(200, 100) = 100.
      ;; Z's inbound limit is 10. A's outbound limit is 50.
      ;; So negotiated inbound for Z is min(10, 50) = 10.
      (is (= 100 (:negotiated-outbound-streams state-z2)))
      (is (= 10 (:negotiated-inbound-streams state-z2))))))
