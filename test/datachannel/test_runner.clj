(ns datachannel.test-runner
  (:require [clojure.test :refer [run-tests]]
            [datachannel.sctp-test]
            [datachannel.dtls-test]
            [datachannel.handshake-test]
            [datachannel.rehandshake-test]
            [datachannel.webrtc-java-test]
            [datachannel.stun-test]
            [datachannel.stun-integration-test]
            [datachannel.stun-webrtc-integration-test]
            [datachannel.sctp-state-machine-test]
            [datachannel.sctp-message-test]
            [datachannel.sctp-establish-simultaneous-lost-data-test]
            [datachannel.sctp-error-chunk-test]
            [datachannel.sctp-reconnect-test]
            [datachannel.sctp-checksum-test]
            [datachannel.sctp-cookie-echo-abort-test]
            [datachannel.sctp-timeout-resends-packet-test]
))

(defn -main [& args]
  (let [{:keys [fail error]}
        (run-tests
         'datachannel.sctp-timeout-resends-packet-test
         'datachannel.sctp-test
         'datachannel.dtls-test
         'datachannel.handshake-test
         'datachannel.rehandshake-test
         'datachannel.webrtc-java-test
         'datachannel.stun-test
         'datachannel.stun-integration-test
         'datachannel.stun-webrtc-integration-test
         'datachannel.sctp-state-machine-test
         'datachannel.sctp-message-test
         'datachannel.sctp-establish-simultaneous-lost-data-test
         'datachannel.sctp-error-chunk-test
         'datachannel.sctp-reconnect-test
         'datachannel.sctp-checksum-test
         'datachannel.sctp-cookie-echo-abort-test
         'datachannel.sctp-timeout-resends-packet-test
)]
    (System/exit (+ fail error))))
