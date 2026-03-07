(ns datachannel.sctp-max-message-size-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest set-max-message-size-test
  (testing "Configured max message size is enforced in send-data"
    (let [connection {:state (atom {:remote-ver-tag 1234
                                    :local-ver-tag 5678
                                    :next-tsn 0
                                    :ssn 0
                                    :state :established})
                      :sctp-out (java.util.concurrent.LinkedBlockingQueue.)}]

      (core/set-max-message-size! connection 42)

      (is (= 42 (:max-message-size @(:state connection))))

      (let [payload (byte-array 43)]
        (try
          (core/send-data connection payload 0 :webrtc/string)
          (is false "Expected exception due to too large payload")
          (catch clojure.lang.ExceptionInfo e
            (is (= :too-large (:type (ex-data e))))
            (is (= "Cannot send too large message" (ex-message e))))))

      ;; Valid payload size should not throw
      (let [payload (byte-array 42)]
        (try
          (core/send-data connection payload 0 :webrtc/string)
          (is true)
          (catch Exception e
            (is false "Did not expect exception for valid payload size")))))))
