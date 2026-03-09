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

      ;; In the new architecture, the `remote-tsn` is only the cum-tsn-ack, meaning it won't update
      ;; to 10 unless all packets in between are received. For testing the math of wraparound though,
      ;; we can manually assert the internal state or adjust to contiguous sequence.
      (let [packet2 {:src-port 5000 :dst-port 5000
                     :chunks [{:type :data :tsn 1 :protocol :webrtc/string :payload (byte-array 0)}]}
            res2 (core/handle-sctp-packet state1 packet2 (System/currentTimeMillis))
            state2 (:new-state res2)]
        (is (= 1 (:remote-tsn state2)) "TSN 1 should be contiguous and update remote-tsn")

        (let [packet3 {:src-port 5000 :dst-port 5000
                       :chunks [{:type :data :tsn 4294967290 :protocol :webrtc/string :payload (byte-array 0)}]}
              res3 (core/handle-sctp-packet state2 packet3 (System/currentTimeMillis))
              state3 (:new-state res3)]
          (is (= 1 (:remote-tsn state3)) "Old TSN 4294967290 should NOT update remote-tsn (still 1)")
          (is (not (contains? (:out-of-order-tsns state3) 4294967290)) "Old TSNs are discarded or ignored as duplicates"))))))