(ns datachannel.sctp-send-a-lot-of-bytes-missed-second-packet-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest send-a-lot-of-bytes-missed-second-packet-test
  (testing "Send A Lot Of Bytes Missed Second Packet"
    (let [now-ms 1000
          state-a (core/create-connection {:mtu 1200} false)
          state-a (:connection state-a)
          state-z (core/create-connection {:mtu 1200} false)
          state-z (:connection state-z)

          ;; A connects: sends INIT
          res-a1 (core/handle-event @(:state state-a) {:type :connect} now-ms)
          state-a1 (:new-state res-a1)
          init-packet (first (:network-out res-a1))

          ;; Z receives INIT and replies with INIT-ACK
          res-z1 (@#'core/handle-sctp-packet @(:state state-z) init-packet now-ms)
          state-z1 (:new-state res-z1)
          init-ack-packet (first (:network-out res-z1))

          ;; A receives INIT-ACK and replies with COOKIE-ECHO
          res-a2 (@#'core/handle-sctp-packet state-a1 init-ack-packet now-ms)
          state-a2 (:new-state res-a2)
          cookie-echo-packet (first (:network-out res-a2))

          ;; Z receives COOKIE-ECHO and replies with COOKIE-ACK
          res-z2 (@#'core/handle-sctp-packet state-z1 cookie-echo-packet now-ms)
          state-z2 (:new-state res-z2)
          cookie-ack-packet (first (:network-out res-z2))

          ;; A receives COOKIE-ACK
          res-a3 (@#'core/handle-sctp-packet state-a2 cookie-ack-packet now-ms)
          state-a3 (:new-state res-a3)]

      ;; Now both A and Z are in the :established state.

      ;; A sends a lot of bytes (e.g. 3000 bytes)
      (let [payload (byte-array 3000 (byte 65))
            res-a4 (core/send-data state-a3 payload 0 :webrtc/binary now-ms)
            state-a4 (:new-state res-a4)
            packets (:network-out res-a4)]
        (is (> (count packets) 1) "A should send multiple packets due to MTU fragmentation")

        ;; Z receives the first packet
        (let [res-z3 (@#'core/handle-sctp-packet state-z2 (first packets) now-ms)
              state-z3 (:new-state res-z3)
              sack-pkt-1 (first (:network-out res-z3))]

          ;; A receives the first SACK
          (let [res-a5 (@#'core/handle-sctp-packet state-a4 sack-pkt-1 now-ms)
                state-a5 (:new-state res-a5)]

            ;; Z *misses* the second packet

            ;; Z receives the third packet
            (let [res-z4 (@#'core/handle-sctp-packet state-z3 (nth packets 2) now-ms)
                  state-z4 (:new-state res-z4)
                  sack-packets (:network-out res-z4)]
              ;; Z should send a SACK indicating the gap
              (is (seq sack-packets) "Z should send a SACK packet")
              (is (= :sack (:type (first (:chunks (first sack-packets))))) "Z should have a SACK chunk")
              (is (= [[2 2]] (:gap-blocks (first (:chunks (first sack-packets))))) "Z should report the gap correctly")

              ;; A receives the SACK packet with gap
              (let [res-a6 (@#'core/handle-sctp-packet state-a5 (first sack-packets) now-ms)
                    state-a6 (:new-state res-a6)]

                ;; A should eventually timeout and retransmit the missing packet
                (let [timeout-ms (+ now-ms 2000) ;; After T3-RTX expires
                      res-a7 (@#'core/handle-timeout state-a6 :t3-rtx timeout-ms)
                      state-a7 (:new-state res-a7)
                      rtx-packets (:network-out res-a7)]
                  (is (seq rtx-packets) "A should retransmit the missing packet")

                  ;; Z receives the missing (second) packet
                  (let [res-z5 (@#'core/handle-sctp-packet state-z4 (first rtx-packets) timeout-ms)
                        state-z5 (:new-state res-z5)
                        final-sack-packets (:network-out res-z5)
                        app-events (:app-events res-z5)]
                    (is (seq final-sack-packets) "Z should send a final SACK packet")
                    (is (some #(= :on-message (:type %)) app-events) "Z should have fired an :on-message event after reassembly")
                    (let [event-payload (:payload (first (filter #(= :on-message (:type %)) app-events)))]
                      (is (= 3000 (count event-payload)) "Z should have received the full 3000 byte payload"))))))))))))
