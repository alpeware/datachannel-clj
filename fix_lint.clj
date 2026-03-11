(ns fix-lint
  (:require [clojure.string :as str]))

(let [content (slurp "test/datachannel/webrtc_integration_test.clj")
      target1 "[datachannel.stun :as stun]\n            "
      new-target1 ""
      target2 "java.net InetAddress InetSocketAddress"
      new-target2 "java.net InetAddress"]
  (-> content
      (str/replace target1 new-target1)
      (str/replace target2 new-target2)
      (as-> s (spit "test/datachannel/webrtc_integration_test.clj" s))))
