(ns fix-test7
  (:require [clojure.string :as str]))

(let [content (slurp "test/datachannel/webrtc_integration_test.clj")
      target "            (println \"Waiting for Java ICE candidate...\")\n            (loop [i 0]\n              (if (or (and @remote-candidate-ip @remote-candidate-port) (> i 50))\n                nil\n                (do (Thread/sleep 100) (recur (inc i)))))"
      new-target "            (println \"Waiting for Java ICE candidate...\")\n            (loop [i 0]\n              (if (or (and @remote-candidate-ip @remote-candidate-port) (> i 100))\n                nil\n                (do (Thread/sleep 100) (recur (inc i)))))"]
  (spit "test/datachannel/webrtc_integration_test.clj" (str/replace content target new-target)))
