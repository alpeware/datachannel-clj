(ns datachannel.test-runner
  (:require [clojure.test :as test]
            [datachannel.sctp-test]
            [datachannel.dtls-test]
            [datachannel.handshake-test]
            [datachannel.integration-test]
            [datachannel.webrtc-java-test]
            [datachannel.stun-integration-test]))

(defn -main []
  (let [{:keys [fail error]} (test/run-tests 'datachannel.sctp-test
                                             'datachannel.dtls-test
                                             'datachannel.handshake-test
                                             'datachannel.webrtc-java-test
                                             'datachannel.stun-integration-test)]
    (if (> (+ fail error) 0)
      (System/exit 1)
      (System/exit 0))))
