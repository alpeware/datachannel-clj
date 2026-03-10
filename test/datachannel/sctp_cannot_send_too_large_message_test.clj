(ns datachannel.sctp-cannot-send-too-large-message-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest cannot-send-too-large-message-test
  (testing "Cannot send a message exceeding max message size"
    (let [state (core/create-connection {} true)
          established-state (assoc state :state :established)
          res (core/set-max-message-size established-state 1000)
          established-state-with-max-size (:new-state res)
          payload (byte-array 1001)
          stream-id 0
          protocol :webrtc/binary
          now-ms 1000]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot send too large message"
                            (core/send-data established-state-with-max-size payload stream-id protocol now-ms))))))
