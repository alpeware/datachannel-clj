(ns datachannel.test-runner
  (:require [clojure.test :as test]
            [datachannel.sctp-test]
            [datachannel.dtls-test]
            [datachannel.handshake-test]
            [datachannel.integration-test]
            [datachannel.webrtc-java-test]
            [datachannel.stun-test]
            [datachannel.stun-integration-test]
            [datachannel.stun-webrtc-integration-test]
            [datachannel.webrtc-integration-test]
            [datachannel.webrtc-extended-test]
            [datachannel.sctp-robustness-test]))

(defn -main []
  (let [{:keys [fail error]} (test/run-tests 'datachannel.sctp-test
                                             'datachannel.dtls-test
                                             'datachannel.handshake-test
                                             'datachannel.integration-test
                                             'datachannel.webrtc-java-test
                                             'datachannel.stun-test
                                             'datachannel.stun-integration-test
                                             'datachannel.stun-webrtc-integration-test
                                             'datachannel.webrtc-integration-test
                                             'datachannel.webrtc-extended-test
                                             'datachannel.sctp-robustness-test)]
    (if (> (+ fail error) 0)
      (System/exit 1)
      (System/exit 0))))
