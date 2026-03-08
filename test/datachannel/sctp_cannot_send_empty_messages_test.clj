(ns datachannel.sctp-cannot-send-empty-messages-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest cannot-send-empty-messages-test
  (testing "Cannot send empty messages via send-data"
    (let [connection (core/create-connection {} true)
          state @(:state (:connection connection))
          established-state (assoc state :state :established)
          payload (byte-array 0)
          stream-id 0
          protocol :webrtc/string
          now-ms 1000]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot send empty message"
                            (core/send-data established-state payload stream-id protocol now-ms))))))
