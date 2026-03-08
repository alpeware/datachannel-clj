(ns datachannel.test-runner
  (:require [clojure.test :refer [run-tests]]
            [datachannel.sctp-test]
            [datachannel.dtls-test]
            [datachannel.handshake-test]
            [datachannel.rehandshake-test]
                        [datachannel.stun-test]
            [datachannel.stun-integration-test]
                        [datachannel.sctp-state-machine-test]
            [datachannel.sctp-message-test]
                        [datachannel.sctp-error-chunk-test]
                        [datachannel.sctp-checksum-test]
            [datachannel.sctp-cookie-echo-abort-test]
            [datachannel.sctp-error-counter-reset-test]
            [datachannel.sctp-wait-for-cookie-ack-test]
            [datachannel.sctp-both-sides-send-heartbeats-test]
            [datachannel.sctp-establish-simultaneous-lost-data-test]
            [datachannel.sctp-init-abort-test]
            [datachannel.sctp-retransmission-test]
            [datachannel.sctp-recovers-on-last-retransmission-test]
            [datachannel.sctp-metrics-test]
            [datachannel.sctp-rx-tx-metrics-increase-test]
            [datachannel.sctp-heartbeat-test]
            [datachannel.sctp-one-peer-reconnects-with-pending-data-test]
            [datachannel.sctp-resent-init-test]
            [datachannel.sctp-retransmission-metrics-test]
            [datachannel.sctp-tsn-wraparound-test]
            [datachannel.sctp-zero-checksum-metrics-test]
            [datachannel.sans-io-integration-test]
            ))

(defn -main [& args]
  (let [{:keys [fail error]}
        (run-tests
                  'datachannel.sctp-test
         'datachannel.dtls-test
         'datachannel.handshake-test
         'datachannel.rehandshake-test
                  'datachannel.stun-test
         'datachannel.stun-integration-test
                  'datachannel.sctp-state-machine-test
         'datachannel.sctp-message-test
                  'datachannel.sctp-error-chunk-test
                  'datachannel.sctp-checksum-test
         'datachannel.sctp-cookie-echo-abort-test
         'datachannel.sctp-error-counter-reset-test
         'datachannel.sctp-wait-for-cookie-ack-test
         'datachannel.sctp-both-sides-send-heartbeats-test
         'datachannel.sctp-establish-simultaneous-lost-data-test
         'datachannel.sctp-init-abort-test
         'datachannel.sctp-retransmission-test
         'datachannel.sctp-recovers-on-last-retransmission-test
         'datachannel.sctp-metrics-test
         'datachannel.sctp-rx-tx-metrics-increase-test
         'datachannel.sctp-heartbeat-test
         'datachannel.sctp-one-peer-reconnects-with-pending-data-test
         'datachannel.sctp-resent-init-test
         'datachannel.sctp-retransmission-metrics-test
         'datachannel.sctp-tsn-wraparound-test
         'datachannel.sctp-zero-checksum-metrics-test
         'datachannel.sans-io-integration-test
         )]
    (System/exit (+ fail error))))
