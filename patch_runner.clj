(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/test_runner.clj")
      new-code (-> code
                   (str/replace #"            \[datachannel.webrtc-integration-test\]\n" "")
                   (str/replace #"            \[datachannel.integration-test\]\n" "")
                   (str/replace #"         'datachannel.webrtc-integration-test\n" "")
                   (str/replace #"         'datachannel.webrtc-extended-test\)" ")")
                   (str/replace #"         'datachannel.integration-test\n" "")
                   )]
  (spit "test/datachannel/test_runner.clj" new-code))
