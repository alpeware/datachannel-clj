(ns datachannel.dcep-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.dcep :as dcep]))

(deftest decode-message-bounds-check-test
  (testing "decoding truncated DATA_CHANNEL_OPEN message returns :unknown without crashing"
    (let [truncated-payload (byte-array [(byte 3) (byte 0) (byte 0)])]
      (is (= {:type :unknown} (dcep/decode-message truncated-payload)))))

  (testing "decoding message with missing label/protocol returns :unknown without crashing"
    (let [bad-payload (byte-array [(byte 3) (byte 0) (byte 0) (byte 0) (byte 0) (byte 0) (byte 0) (byte 0) (byte 0) (byte 10) (byte 0) (byte 10)])]
      (is (= {:type :unknown} (dcep/decode-message bad-payload)))))

  (testing "decoding empty payload returns :unknown without crashing"
    (let [empty-payload (byte-array [])]
      (is (= {:type :unknown} (dcep/decode-message empty-payload))))))
