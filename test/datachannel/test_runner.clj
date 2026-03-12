(ns datachannel.test-runner
  (:require [clojure.test :refer [run-tests]]
            [datachannel.listen-integration-test]
            [datachannel.sctp-test]
            [datachannel.sctp-send-message-with-limited-rtx-test]
            [datachannel.sctp-send-many-fragmented-messages-with-limited-rtx-test]
            [datachannel.dtls-test]
            [datachannel.handshake-test]
            [datachannel.rehandshake-test]
            [datachannel.stun-test]
            [datachannel.stun-integration-test]
            [datachannel.stun-webrtc-integration-test]
            [datachannel.sctp-state-machine-test]
            [datachannel.sctp-establish-simultaneous-connection-test]
            [datachannel.sctp-error-chunk-test]
            [datachannel.sctp-checksum-test]
            [datachannel.sctp-cookie-echo-abort-test]
            [datachannel.sctp-error-counter-reset-test]
            [datachannel.sctp-sending-heartbeat-answers-with-ack-test]
            [datachannel.sctp-expect-heartbeat-to-be-sent-test]
            [datachannel.sctp-expect-heartbeats-not-sent-when-sending-data-test]
            [datachannel.sctp-both-sides-send-heartbeats-test]
            [datachannel.sctp-close-after-first-lost-heartbeat-test]
            [datachannel.sctp-close-after-second-lost-heartbeat-test]
            [datachannel.sctp-close-after-too-many-retransmissions-test]
            [datachannel.sctp-establish-connection-test]
            [datachannel.sctp-init-abort-test]
            [datachannel.sctp-metrics-test]
            [datachannel.sctp-resent-init-test]
            [datachannel.sctp-tsn-wraparound-test]
            [datachannel.sctp-zero-checksum-metrics-test]
            [datachannel.sans-io-integration-test]
            [datachannel.sctp-doesnt-send-more-packets-until-cookie-ack-has-been-received-test]
            [datachannel.sctp-resend-init-and-establish-connection-test]
            [datachannel.sctp-resend-cookie-echo-and-establish-connection-test]
            [datachannel.sctp-exposes-the-number-of-negotiated-streams-test]
            [datachannel.sctp-shutdown-timer-test]
            [datachannel.sctp-establish-connection-lost-cookie-ack-test]
            [datachannel.sctp-attempt-connect-without-cookie-test]
            [datachannel.sctp-shutdown-connection-test]
            [datachannel.sctp-send-message-after-established-test]
            [datachannel.sctp-cannot-send-empty-messages-test]
            [datachannel.sctp-establish-connection-while-sending-data-test]
            [datachannel.sctp-close-after-too-many-lost-heartbeats-test]
            [datachannel.sctp-recovers-after-successful-ack-test]
            [datachannel.sctp-timeout-resends-packet-test]
            [datachannel.sctp-close-after-first-failed-transmission-test]
            [datachannel.sctp-init-ack-robustness-test]
            [datachannel.sctp-reconnect-test]
            [datachannel.sctp-unknown-chunk-test]
            [datachannel.sctp-send-many-api-method-test]
            [datachannel.sctp-send-a-lot-of-bytes-missed-second-packet-test]
            [datachannel.sctp-close-after-one-failed-retransmission-test]
            [datachannel.sctp-recover-on-last-retransmission-test]
            [datachannel.pure-p2p-integration-test]
            [datachannel.webrtc-integration-test]
            [datachannel.api-test]
            [datachannel.sctp-set-max-message-size-test]
            [datachannel.sctp-cannot-send-too-large-message-test]
            [datachannel.fuzz-test]
            [datachannel.sctp-gen-test]
            [datachannel.webrtc-java-test]))

(def test-groups
  [;; Group 1: Stateful/Integration tests (run sequentially relative to each other)
   ['datachannel.listen-integration-test
    'datachannel.stun-webrtc-integration-test
    'datachannel.webrtc-integration-test
    'datachannel.webrtc-java-test
    'datachannel.stun-integration-test
    'datachannel.sans-io-integration-test
    'datachannel.pure-p2p-integration-test]

   ;; Group 2: Pure/Stateless tests part 1
   ['datachannel.sctp-test
    'datachannel.sctp-send-message-with-limited-rtx-test
    'datachannel.sctp-send-many-fragmented-messages-with-limited-rtx-test
    'datachannel.dtls-test
    'datachannel.handshake-test
    'datachannel.rehandshake-test
    'datachannel.stun-test
    'datachannel.sctp-state-machine-test
    'datachannel.sctp-establish-simultaneous-connection-test
    'datachannel.sctp-error-chunk-test
    'datachannel.sctp-checksum-test
    'datachannel.sctp-cookie-echo-abort-test]

   ;; Group 3: Pure/Stateless tests part 2
   ['datachannel.sctp-error-counter-reset-test
    'datachannel.sctp-sending-heartbeat-answers-with-ack-test
    'datachannel.sctp-expect-heartbeat-to-be-sent-test
    'datachannel.sctp-expect-heartbeats-not-sent-when-sending-data-test
    'datachannel.sctp-both-sides-send-heartbeats-test
    'datachannel.sctp-close-after-first-lost-heartbeat-test
    'datachannel.sctp-close-after-second-lost-heartbeat-test
    'datachannel.sctp-close-after-too-many-retransmissions-test
    'datachannel.sctp-establish-connection-test
    'datachannel.sctp-init-abort-test
    'datachannel.sctp-metrics-test
    'datachannel.sctp-resent-init-test]

   ;; Group 4: Pure/Stateless tests part 3
   ['datachannel.sctp-tsn-wraparound-test
    'datachannel.sctp-exposes-the-number-of-negotiated-streams-test
    'datachannel.sctp-zero-checksum-metrics-test
    'datachannel.sctp-doesnt-send-more-packets-until-cookie-ack-has-been-received-test
    'datachannel.sctp-resend-init-and-establish-connection-test
    'datachannel.sctp-resend-cookie-echo-and-establish-connection-test
    'datachannel.sctp-shutdown-timer-test
    'datachannel.sctp-establish-connection-lost-cookie-ack-test
    'datachannel.sctp-attempt-connect-without-cookie-test
    'datachannel.sctp-shutdown-connection-test
    'datachannel.sctp-send-message-after-established-test
    'datachannel.sctp-cannot-send-empty-messages-test
    'datachannel.sctp-establish-connection-while-sending-data-test]

   ;; Group 5: Pure/Stateless tests part 4 + generative tests
   ['datachannel.sctp-close-after-too-many-lost-heartbeats-test
    'datachannel.sctp-recovers-after-successful-ack-test
    'datachannel.sctp-timeout-resends-packet-test
    'datachannel.sctp-close-after-first-failed-transmission-test
    'datachannel.sctp-init-ack-robustness-test
    'datachannel.sctp-reconnect-test
    'datachannel.sctp-unknown-chunk-test
    'datachannel.sctp-send-many-api-method-test
    'datachannel.sctp-send-a-lot-of-bytes-missed-second-packet-test
    'datachannel.sctp-close-after-one-failed-retransmission-test
    'datachannel.sctp-recover-on-last-retransmission-test
    'datachannel.api-test
    'datachannel.sctp-set-max-message-size-test
    'datachannel.sctp-cannot-send-too-large-message-test
    'datachannel.fuzz-test
    'datachannel.sctp-gen-test]])

(defn -main [& _args]
  (let [results (pmap #(apply run-tests %) test-groups)
        fail (reduce + (map :fail results))
        error (reduce + (map :error results))]
    (System/exit (+ fail error))))
