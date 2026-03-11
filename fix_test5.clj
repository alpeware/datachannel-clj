(ns fix-test5
  (:require [clojure.string :as str]))

(let [content (slurp "test/datachannel/webrtc_integration_test.clj")
      target "            (api/start! node\n                        {:ip (or @remote-candidate-ip local-ip)\n                         :port (or @remote-candidate-port port)\n                         :remote-ice-ufrag (:ufrag @remote-creds)\n                         :remote-ice-pwd (:pwd @remote-creds)}\n                        callbacks)\n            (when (and @remote-candidate-ip @remote-candidate-port)\n              (datachannel.api/add-remote-candidate! node @remote-candidate-ip @remote-candidate-port))"
      new-target "            (api/start! node\n                        {:ip (or @remote-candidate-ip local-ip)\n                         :port (or @remote-candidate-port port)\n                         :remote-ice-ufrag (:ufrag @remote-creds)\n                         :remote-ice-pwd (:pwd @remote-creds)}\n                        callbacks)"]
  (spit "test/datachannel/webrtc_integration_test.clj" (str/replace content target new-target)))
