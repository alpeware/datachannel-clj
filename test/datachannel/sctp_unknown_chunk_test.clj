(ns datachannel.sctp-unknown-chunk-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest receiving-unknown-chunk-responds-with-error-test
  (testing "Receiving Unknown Chunk Responds With Error (upper bits 01)"
    (let [initial-state {:remote-ver-tag 1234
                         :next-tsn 1000
                         :ssn 0
                         :state :established
                         :streams {0 {:send-queue [] :recv-queue []}}
                         :pending-control-chunks []
                         :metrics {}}
          unknown-chunk {:type 0x49 ;; 0x49 = 01001001 binary, upper bits are 01
                         :flags 0
                         :length 8
                         :body (byte-array [1 2 3 4])}
          packet {:src-port 5000
                  :dst-port 5001
                  :verification-tag 0
                  :chunks [unknown-chunk]}
          res (core/handle-sctp-packet initial-state packet (System/currentTimeMillis))
          network-out (:network-out res)

          error-packet (first network-out)]
      (is error-packet "Connection should send an ERROR packet in response to unknown chunk with 01 upper bits")
      (when error-packet
        (let [error-chunk (first (:chunks error-packet))]
          (is (= :error (:type error-chunk)))
          (is (= 1 (count (:causes error-chunk))))
          (let [cause (first (:causes error-chunk))]
            (is (= 6 (:cause-code cause)) "Cause code should be 6 (Unrecognized Chunk Type)")
            (is (= (seq [1 2 3 4]) (seq (:chunk-data cause))) "Cause should contain the original chunk data")))))))
