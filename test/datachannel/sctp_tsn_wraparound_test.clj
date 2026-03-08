(ns datachannel.sctp-tsn-wraparound-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest tsn-wraparound-test
  (testing "TSN wraparound handling"
    (let [state {:remote-tsn 4294967295 :remote-ver-tag 123}
          packet1 {:src-port 5000 :dst-port 5000
                   :chunks [{:type :data :tsn 0 :protocol :webrtc/string :payload (byte-array 0)}]}

          res1 (core/handle-sctp-packet state packet1 (System/currentTimeMillis))
          state1 (:new-state res1)]

      (is (= 0 (:remote-tsn state1)) "TSN 0 should be considered newer than 4294967295")

      (let [packet2 {:src-port 5000 :dst-port 5000
                     :chunks [{:type :data :tsn 10 :protocol :webrtc/string :payload (byte-array 0)}]}
            res2 (core/handle-sctp-packet state1 packet2 (System/currentTimeMillis))
            state2 (:new-state res2)]
        (is (= 10 (:remote-tsn state2)) "TSN 10 should be considered newer than 0")

        (let [packet3 {:src-port 5000 :dst-port 5000
                       :chunks [{:type :data :tsn 5 :protocol :webrtc/string :payload (byte-array 0)}]}
              res3 (core/handle-sctp-packet state2 packet3 (System/currentTimeMillis))
              state3 (:new-state res3)]
          (is (= 10 (:remote-tsn state3)) "Old TSN 5 should NOT update remote-tsn (still 10)"))))))