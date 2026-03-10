(ns datachannel.sctp-reconnect-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest one-peer-reconnects-test
  (testing "One Peer Reconnects"
    (let [now 1000
          state-a {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :state :closed}
          state-z {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0 :state :closed}

      ;; 1. Establish connection between A and Z
          state-a1 (merge state-a {:state :cookie-wait :init-tag 1111})
          init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                       :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                 :outbound-streams 10 :inbound-streams 10
                                 :initial-tsn 100 :params {}}]}

          res-z1 (@#'core/handle-sctp-packet state-z init-packet now)
          state-z1 (:new-state res-z1)
          init-ack-packet (first (:network-out res-z1))

          res-a1 (@#'core/handle-sctp-packet state-a1 init-ack-packet now)
          state-a2 (:new-state res-a1)
          cookie-echo-packet (first (:network-out res-a1))

          res-z2 (@#'core/handle-sctp-packet state-z1 cookie-echo-packet now)
          state-z2 (:new-state res-z2)
          cookie-ack-packet (first (:network-out res-z2))

          res-a2 (@#'core/handle-sctp-packet state-a2 cookie-ack-packet now)
          state-a3 (:new-state res-a2)]

      (is (= :established (:state state-a3)) "A should be established")
      (is (= :established (:state state-z2)) "Z should be established")

      ;; 2. A "reconnects" - starts a new connection attempt from scratch
      ;; This simulates A crashing or restarting and trying to connect to Z again.
      (let [state-a-reconnect {:remote-ver-tag 0 :local-ver-tag 3333 :next-tsn 300 :ssn 0 :state :cookie-wait :init-tag 3333}

        ;; A2 sends a new INIT to Z
            init-packet2 {:src-port 5000 :dst-port 5001 :verification-tag 0
                          :chunks [{:type :init :init-tag 3333 :a-rwnd 100000
                                    :outbound-streams 10 :inbound-streams 10
                                    :initial-tsn 300 :params {}}]}
            res-z3 (@#'core/handle-sctp-packet state-z2 init-packet2 now)
            state-z3 (:new-state res-z3)
            init-ack-packet2 (first (:network-out res-z3))]

        ;; Z should reply with INIT-ACK, despite being established
        (is init-ack-packet2 "Z should reply with INIT-ACK to the new INIT")
        (is (= :init-ack (-> init-ack-packet2 :chunks first :type)))

        (let [res-a-reconnect1 (@#'core/handle-sctp-packet state-a-reconnect init-ack-packet2 now)
              state-a-reconnect1 (:new-state res-a-reconnect1)
              cookie-echo-packet2 (first (:network-out res-a-reconnect1))]

          ;; A2 sends COOKIE-ECHO
          (is cookie-echo-packet2 "A2 should send COOKIE-ECHO")
          (is (= :cookie-echo (-> cookie-echo-packet2 :chunks first :type)))

          (let [res-z4 (@#'core/handle-sctp-packet state-z3 cookie-echo-packet2 now)
                state-z4 (:new-state res-z4)
                cookie-ack-packet2 (first (:network-out res-z4))]

            ;; Z should reply with COOKIE-ACK and reset its state
            (is cookie-ack-packet2 "Z should reply with COOKIE-ACK to the new COOKIE-ECHO")
            (is (= :cookie-ack (-> cookie-ack-packet2 :chunks first :type)))

            (let [res-a-reconnect2 (@#'core/handle-sctp-packet state-a-reconnect1 cookie-ack-packet2 now)
                  state-a-reconnect2 (:new-state res-a-reconnect2)]

              (is (= :established (:state state-a-reconnect2)) "A2 should be established")
              (is (= :established (:state state-z4)) "Z should remain established")
              (is (= 3333 (:remote-ver-tag state-z4)) "Z should have updated its remote verification tag to A2's tag"))))))))
