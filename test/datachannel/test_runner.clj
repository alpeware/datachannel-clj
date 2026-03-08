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
                        [datachannel.sctp-error-chunk-test]
                        [datachannel.sctp-checksum-test]
            [datachannel.sctp-cookie-echo-abort-test]
            [datachannel.sctp-error-counter-reset-test]
            [datachannel.sctp-wait-for-cookie-ack-test]
            [datachannel.sctp-both-sides-send-heartbeats-test]
            ))

(defn -main [& args]
  (let [{:keys [fail error]}
        (run-tests
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
                  'datachannel.sctp-error-chunk-test
                  'datachannel.sctp-checksum-test
         'datachannel.sctp-cookie-echo-abort-test
         'datachannel.sctp-error-counter-reset-test
         'datachannel.sctp-wait-for-cookie-ack-test
         'datachannel.sctp-both-sides-send-heartbeats-test
         )]
    (System/exit (+ fail error))))
