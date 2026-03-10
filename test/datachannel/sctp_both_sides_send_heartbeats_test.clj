(ns datachannel.sctp-both-sides-send-heartbeats-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest both-sides-send-heartbeats-test
  (testing "Both sides send heartbeats"
    ;; Testing that on an idle connection, both sides send heartbeats, and both sides ack.
    ;; We use slightly different heartbeat intervals to validate that sending an ack
    ;; doesn't restart the heartbeat timer.
    (let [now (System/currentTimeMillis)
          interval-a 1000
          interval-z 1100

          ;; Initial states for side A and Z
          state-a {:state :established
                   :remote-ver-tag 2222
                   :local-ver-tag 1111
                   :src-port 5000
                   :dst-port 5001
                   :next-tsn 1000
                   :ssn 0
                   :timers {:sctp/t-heartbeat {:expires-at (+ now interval-a)}}
                   :heartbeat-interval interval-a
                   :heartbeat-error-count 0
                   :rto-initial 1000
                   :max-retransmissions 10}

          state-z {:state :established
                   :remote-ver-tag 1111
                   :local-ver-tag 2222
                   :src-port 5001
                   :dst-port 5000
                   :next-tsn 2000
                   :ssn 0
                   :timers {:sctp/t-heartbeat {:expires-at (+ now interval-z)}}
                   :heartbeat-interval interval-z
                   :heartbeat-error-count 0
                   :rto-initial 1000
                   :max-retransmissions 10}

          ;; Advance time by interval-a, triggering A's heartbeat
          time-1 (+ now interval-a)
          res-a1 (core/handle-timeout state-a :sctp/t-heartbeat time-1)
          state-a1 (:new-state res-a1)
          hb-req-a (first (:network-out res-a1))
          _ (is (some? hb-req-a) "A should send a heartbeat")
          _ (is (= :heartbeat (:type (first (:chunks hb-req-a)))) "A sent a heartbeat chunk")

            ;; Z receives A's heartbeat and sends an ACK
          res-z2 (core/handle-sctp-packet state-z hb-req-a time-1)
          state-z1 (:new-state res-z2)
          hb-ack-z (first (:network-out res-z2))
          _ (is (some? hb-ack-z) "Z should send a heartbeat ack")
          _ (is (= :heartbeat-ack (:type (first (:chunks hb-ack-z)))) "Z sent a heartbeat ack chunk")

            ;; A receives Z's heartbeat ack
          res-a2 (core/handle-sctp-packet state-a1 hb-ack-z time-1)
          state-a2 (:new-state res-a2)

            ;; Advance time to interval-z, triggering Z's heartbeat
          time-2 (+ now interval-z)
          res-z3 (core/handle-timeout state-z1 :sctp/t-heartbeat time-2)
          state-z2 (:new-state res-z3)
          hb-req-z (first (:network-out res-z3))
          _ (is (some? hb-req-z) "Z should send a heartbeat")
          _ (is (= :heartbeat (:type (first (:chunks hb-req-z)))) "Z sent a heartbeat chunk")

            ;; A receives Z's heartbeat and sends an ACK
          res-a3 (core/handle-sctp-packet state-a2 hb-req-z time-2)
          state-a3 (:new-state res-a3)
          hb-ack-a (first (:network-out res-a3))
          _ (is (some? hb-ack-a) "A should send a heartbeat ack")
          _ (is (= :heartbeat-ack (:type (first (:chunks hb-ack-a)))) "A sent a heartbeat ack chunk")

            ;; Z receives A's heartbeat ack
          res-z4 (core/handle-sctp-packet state-z2 hb-ack-a time-2)
          state-z3 (:new-state res-z4)
          _ (is (= 0 (:heartbeat-error-count state-z3)) "Z error count reset")
          _ (is (= 0 (:heartbeat-error-count state-a3)) "A error count reset")]
      state-z3)))
