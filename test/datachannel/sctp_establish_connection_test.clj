(ns datachannel.sctp-establish-connection-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest establish-connection-test
  (testing "Establish Connection"
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
          _ (is init-ack-packet "Z should send INIT-ACK")
          _ (is (= :init-ack (:type (first (:chunks init-ack-packet)))))

          ;; A receives INIT-ACK and replies with COOKIE-ECHO
          res-a1 (@#'core/handle-sctp-packet state-a1 init-ack-packet now-ms)
          state-a2 (:new-state res-a1)
          cookie-echo-packet (first (:network-out res-a1))
          _ (is cookie-echo-packet "A should send COOKIE-ECHO")
          _ (is (= :cookie-echo (:type (first (:chunks cookie-echo-packet)))))

          ;; Z receives COOKIE-ECHO and replies with COOKIE-ACK
          res-z2 (@#'core/handle-sctp-packet state-z1 cookie-echo-packet now-ms)
          state-z2 (:new-state res-z2)
          cookie-ack-packet (first (:network-out res-z2))
          _ (is cookie-ack-packet "Z should send COOKIE-ACK")
          _ (is (= :cookie-ack (:type (first (:chunks cookie-ack-packet)))))
          _ (is (= :established (:state state-z2)) "Z should be established")
          _ (is (some #(= :on-open (:type %)) (:app-events res-z2)) "Z should have fired on-open event")

          ;; A receives COOKIE-ACK
          res-a2 (@#'core/handle-sctp-packet state-a2 cookie-ack-packet now-ms)
          state-a3 (:new-state res-a2)]

      (is (= :established (:state state-a3)) "A should be established")
      (is (some #(= :on-open (:type %)) (:app-events res-a2)) "A should have fired on-open event")
      (is (empty? (:network-out res-a2)) "A should not send more packets"))))
