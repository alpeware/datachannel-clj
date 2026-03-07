(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/test_runner.clj")
      new-code (-> code
                   (str/replace #"\n         'datachannel.sctp-timeout-resends-packet-test\)"
                                "\n         'datachannel.sctp-timeout-resends-packet-test\n         'datachannel.integration-test\n         'datachannel.webrtc-integration-test\n         'datachannel.webrtc-extended-test)")
                   (str/replace #"            \[datachannel.sctp-timeout-resends-packet-test\]\n"
                                "            [datachannel.sctp-timeout-resends-packet-test]\n            [datachannel.integration-test]\n            [datachannel.webrtc-integration-test]\n            [datachannel.webrtc-extended-test]\n")
                   )]
  (spit "test/datachannel/test_runner.clj" new-code))
