(ns datachannel.test-runner
  (:require [clojure.test :refer [run-tests]]
            [datachannel.sctp-test]
            [datachannel.dtls-test]
            [datachannel.handshake-test]
            [datachannel.rehandshake-test]
            [datachannel.integration-test]
            [datachannel.webrtc-java-test]
            [datachannel.stun-test]
            [datachannel.stun-integration-test]
            [datachannel.stun-webrtc-integration-test]
            [datachannel.webrtc-integration-test]
            [datachannel.webrtc-extended-test]
            [datachannel.sctp-robustness-test]
            [datachannel.sctp-state-machine-test]
            [datachannel.sctp-init-ack-robustness-test]
            [datachannel.sctp-message-test]
            [datachannel.sctp-establish-simultaneous-lost-data-test]
            [datachannel.sctp-unknown-chunk-test]
            [datachannel.sctp-error-chunk-test]
            [datachannel.sctp-max-message-size-test]
            [datachannel.sctp-reconnect-test]
            [datachannel.sctp-checksum-test]
            [datachannel.sctp-init-abort-test]
            [datachannel.sctp-cookie-echo-abort-test]
            [datachannel.sctp-timeout-resends-packet-test]))

(defn -main [& args]
  (let [{:keys [fail error]}
        (run-tests
         'datachannel.sctp-timeout-resends-packet-test
         'datachannel.sctp-test
         'datachannel.dtls-test
         'datachannel.handshake-test
         'datachannel.rehandshake-test
         'datachannel.integration-test
         'datachannel.webrtc-java-test
         'datachannel.stun-test
         'datachannel.stun-integration-test
         'datachannel.stun-webrtc-integration-test
         'datachannel.webrtc-integration-test
         'datachannel.webrtc-extended-test
         'datachannel.sctp-robustness-test
         'datachannel.sctp-state-machine-test
         'datachannel.sctp-init-ack-robustness-test
         'datachannel.sctp-message-test
         'datachannel.sctp-establish-simultaneous-lost-data-test
         'datachannel.sctp-unknown-chunk-test
         'datachannel.sctp-error-chunk-test
         'datachannel.sctp-max-message-size-test
         'datachannel.sctp-reconnect-test
         'datachannel.sctp-checksum-test
         'datachannel.sctp-init-abort-test
         'datachannel.sctp-cookie-echo-abort-test)]
    (System/exit (+ fail error))))
