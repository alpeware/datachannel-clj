(ns datachannel.test-runner
  (:require [clojure.test :refer [run-tests]]
            [datachannel.sctp-test]
            [datachannel.dtls-test]
            [datachannel.handshake-test]
            [datachannel.rehandshake-test]
                        [datachannel.stun-test]
            [datachannel.stun-integration-test]
                        [datachannel.sctp-state-machine-test]
            [datachannel.sctp-establish-simultaneous-connection-test]
                        [datachannel.sctp-error-chunk-test]
                        [datachannel.sctp-checksum-test]
            [datachannel.sctp-cookie-echo-abort-test]
            [datachannel.sctp-error-counter-reset-test]
            [datachannel.sctp-both-sides-send-heartbeats-test]
            [datachannel.sctp-close-after-first-lost-heartbeat-test]
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
            [datachannel.sctp-shutdown-timer-test]
            [datachannel.sctp-establish-connection-lost-cookie-ack-test]
            [datachannel.sctp-attempt-connect-without-cookie-test]
            [datachannel.sctp-shutdown-connection-test]
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
                  'datachannel.sctp-establish-simultaneous-connection-test
                  'datachannel.sctp-error-chunk-test
                  'datachannel.sctp-checksum-test
         'datachannel.sctp-cookie-echo-abort-test
         'datachannel.sctp-error-counter-reset-test
         'datachannel.sctp-both-sides-send-heartbeats-test
         'datachannel.sctp-close-after-first-lost-heartbeat-test
         'datachannel.sctp-establish-connection-test
         'datachannel.sctp-init-abort-test
         'datachannel.sctp-metrics-test
         'datachannel.sctp-resent-init-test
         'datachannel.sctp-tsn-wraparound-test
         'datachannel.sctp-zero-checksum-metrics-test
         'datachannel.sans-io-integration-test
         'datachannel.sctp-doesnt-send-more-packets-until-cookie-ack-has-been-received-test
         'datachannel.sctp-resend-init-and-establish-connection-test
         'datachannel.sctp-resend-cookie-echo-and-establish-connection-test
         'datachannel.sctp-shutdown-timer-test
         'datachannel.sctp-establish-connection-lost-cookie-ack-test
         'datachannel.sctp-attempt-connect-without-cookie-test
         'datachannel.sctp-shutdown-connection-test
         )]
    (System/exit (+ fail error))))
