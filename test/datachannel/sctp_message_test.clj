(ns datachannel.sctp-message-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest cannot-send-empty-messages-test
  (testing "Cannot Send Empty Messages"
    (let [out (java.util.concurrent.LinkedBlockingQueue.)
          state (atom {:remote-ver-tag 0 :next-tsn 0 :ssn 0})
          conn {:sctp-out out :state state}
          payload (byte-array 0)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot send empty message"
            (core/send-data conn payload 1 :webrtc/string))))))

(deftest cannot-send-too-large-message-test
  (testing "Cannot Send Too Large Message"
    (let [out (java.util.concurrent.LinkedBlockingQueue.)
          state (atom {:remote-ver-tag 0 :next-tsn 0 :ssn 0})
          conn {:sctp-out out :state state}
          ;; Max chunk size is 65535, DATA header is 16 bytes. Max payload is 65519.
          payload (byte-array 65520)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot send too large message"
            (core/send-data conn payload 1 :webrtc/string))))))
