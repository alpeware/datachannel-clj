(ns datachannel.sctp-establish-simultaneous-connection-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest establish-simultaneous-connection-test
  (testing "Establish Simultaneous Connection"
    (let [now-ms 1000
          state-a {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed}
          state-z {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed}

          ;; A and Z connect simultaneously: both send INIT
          state-a1 (merge state-a {:state :cookie-wait :init-tag 1111})
          state-z1 (merge state-z {:state :cookie-wait :init-tag 2222})

          init-packet-a {:src-port 5000 :dst-port 5001 :verification-tag 0
                         :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                   :outbound-streams 1 :inbound-streams 1
                                   :initial-tsn 1000 :params {}}]}

          init-packet-z {:src-port 5001 :dst-port 5000 :verification-tag 0
                         :chunks [{:type :init :init-tag 2222 :a-rwnd 100000
                                   :outbound-streams 1 :inbound-streams 1
                                   :initial-tsn 2000 :params {}}]}

          ;; A receives Z's INIT, replies with INIT-ACK
          res-a1 (@#'core/handle-sctp-packet state-a1 init-packet-z now-ms)
          state-a2 (:new-state res-a1)
          init-ack-from-a (first (:network-out res-a1))
          _ (is init-ack-from-a "A should send INIT-ACK")
          _ (is (= :init-ack (:type (first (:chunks init-ack-from-a)))))

          ;; Z receives A's INIT, replies with INIT-ACK
          res-z1 (@#'core/handle-sctp-packet state-z1 init-packet-a now-ms)
          state-z2 (:new-state res-z1)
          init-ack-from-z (first (:network-out res-z1))
          _ (is init-ack-from-z "Z should send INIT-ACK")
          _ (is (= :init-ack (:type (first (:chunks init-ack-from-z)))))

          ;; A receives Z's INIT-ACK, replies with COOKIE-ECHO
          res-a2 (@#'core/handle-sctp-packet state-a2 init-ack-from-z now-ms)
          state-a3 (:new-state res-a2)
          cookie-echo-from-a (first (:network-out res-a2))
          _ (is cookie-echo-from-a "A should send COOKIE-ECHO")
          _ (is (= :cookie-echo (:type (first (:chunks cookie-echo-from-a)))))

          ;; Z receives A's INIT-ACK, replies with COOKIE-ECHO
          res-z2 (@#'core/handle-sctp-packet state-z2 init-ack-from-a now-ms)
          state-z3 (:new-state res-z2)
          cookie-echo-from-z (first (:network-out res-z2))
          _ (is cookie-echo-from-z "Z should send COOKIE-ECHO")
          _ (is (= :cookie-echo (:type (first (:chunks cookie-echo-from-z)))))

          ;; A receives Z's COOKIE-ECHO, replies with COOKIE-ACK and establishes
          res-a3 (@#'core/handle-sctp-packet state-a3 cookie-echo-from-z now-ms)
          state-a4 (:new-state res-a3)
          cookie-ack-from-a (first (:network-out res-a3))
          _ (is cookie-ack-from-a "A should send COOKIE-ACK")
          _ (is (= :cookie-ack (:type (first (:chunks cookie-ack-from-a)))))
          _ (is (= :established (:state state-a4)) "A should be established")
          _ (is (some #(= :on-open (:type %)) (:app-events res-a3)) "A should have fired on-open event")

          ;; Z receives A's COOKIE-ECHO, replies with COOKIE-ACK and establishes
          res-z3 (@#'core/handle-sctp-packet state-z3 cookie-echo-from-a now-ms)
          state-z4 (:new-state res-z3)
          cookie-ack-from-z (first (:network-out res-z3))
          _ (is cookie-ack-from-z "Z should send COOKIE-ACK")
          _ (is (= :cookie-ack (:type (first (:chunks cookie-ack-from-z)))))
          _ (is (= :established (:state state-z4)) "Z should be established")
          _ (is (some #(= :on-open (:type %)) (:app-events res-z3)) "Z should have fired on-open event")

          ;; A receives Z's COOKIE-ACK (silently processed/ignored since it's already established)
          res-a4 (@#'core/handle-sctp-packet state-a4 cookie-ack-from-z now-ms)
          state-a5 (:new-state res-a4)
          _ (is (= :established (:state state-a5)) "A should remain established")
          _ (is (empty? (:network-out res-a4)) "A should not send anything back")

          ;; Z receives A's COOKIE-ACK (silently processed/ignored since it's already established)
          res-z4 (@#'core/handle-sctp-packet state-z4 cookie-ack-from-a now-ms)
          state-z5 (:new-state res-z4)]

      (is (= :established (:state state-z5)) "Z should remain established")
      (is (empty? (:network-out res-z4)) "Z should not send anything back"))))
