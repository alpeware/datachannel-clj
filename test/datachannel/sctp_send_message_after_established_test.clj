(ns datachannel.sctp-send-message-after-established-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest send-message-after-established-test
  (testing "Send Message After Established"
    (let [now-ms 1000
          state-a {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed}
          state-z {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed}

          ;; A connects: sends INIT
          state-a1 (merge state-a {:state :cookie-wait :init-tag 1111})
          init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                       :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                 :outbound-streams 1 :inbound-streams 1
                                 :initial-tsn 1000 :params {}}]}

          ;; Z receives INIT and replies with INIT-ACK
          res-z1 (@#'core/handle-sctp-packet state-z init-packet now-ms)
          state-z1 (:new-state res-z1)
          init-ack-packet (first (:network-out res-z1))

          ;; A receives INIT-ACK and replies with COOKIE-ECHO
          res-a1 (@#'core/handle-sctp-packet state-a1 init-ack-packet now-ms)
          state-a2 (:new-state res-a1)
          cookie-echo-packet (first (:network-out res-a1))

          ;; Z receives COOKIE-ECHO and replies with COOKIE-ACK
          res-z2 (@#'core/handle-sctp-packet state-z1 cookie-echo-packet now-ms)
          state-z2 (:new-state res-z2)
          cookie-ack-packet (first (:network-out res-z2))

          ;; A receives COOKIE-ACK
          res-a2 (@#'core/handle-sctp-packet state-a2 cookie-ack-packet now-ms)
          state-a3 (:new-state res-a2)

          ;; Now both A and Z are in the :established state.

          ;; A sends a message
          payload (.getBytes "Hello SCTP" "UTF-8")
          res-a3-send (core/send-data state-a3 payload 0 :webrtc/string now-ms)
          state-a4 (:new-state res-a3-send)
          data-packet (first (:network-out res-a3-send))
          _ (is data-packet "A should send a DATA packet")
          _ (is (= :data (:type (first (:chunks data-packet)))) "A should have a DATA chunk")
          _ (is (= 1 (count (:send-queue (get-in state-a4 [:streams 0])))) "A should have unacknowledged data in its stream send-queue")

          ;; Z receives the DATA message
          res-z3 (@#'core/handle-sctp-packet state-z2 data-packet now-ms)
          _state-z3 (:new-state res-z3)
          sack-packet (first (:network-out res-z3))
          app-events (:app-events res-z3)
          _ (is sack-packet "Z should send a SACK packet")
          _ (is (= :sack (:type (first (:chunks sack-packet)))) "Z should have a SACK chunk")
          _ (is (some #(= :on-message (:type %)) app-events) "Z should have fired an :on-message event")
          _ (is (= "Hello SCTP" (String. ^bytes (:payload (first (filter #(= :on-message (:type %)) app-events))) "UTF-8")) "Z should have received the correct payload")

          ;; A receives the SACK packet
          res-a4 (@#'core/handle-sctp-packet state-a4 sack-packet now-ms)
          state-a5 (:new-state res-a4)]
      (is (empty? (:send-queue (get-in state-a5 [:streams 0]))) "A should have cleared its stream send-queue after receiving the SACK"))))
