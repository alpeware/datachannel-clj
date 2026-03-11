(ns datachannel.sctp-metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.chunks :as chunks]
            [datachannel.core :as core]))

(deftest initial-metrics-are-unset-test
  (testing "Initial Metrics Are Unset"
    (let [state (@#'core/create-connection {} true)
          metrics (:metrics state)]
      (is (not (nil? metrics)) "Metrics map should be initialized")
      (is (= 0 (:tx-packets metrics)) "tx-packets should be 0")
      (is (= 0 (:rx-packets metrics)) "rx-packets should be 0")
      (is (= 0 (:tx-bytes metrics)) "tx-bytes should be 0")
      (is (= 0 (:rx-bytes metrics)) "rx-bytes should be 0")
      (is (= 0 (:retransmissions metrics)) "retransmissions should be 0")
      (is (= 0 (:unacked-data metrics)) "unacked-data should be 0"))))

(deftest retransmission-metrics-are-set-for-normal-retransmit-test
  (testing "Retransmission Metrics Are Set For Normal Retransmit"
    (let [init-state (core/create-connection {} true)
          state1 (assoc init-state :state :established)
          state2 (-> state1
                     (assoc-in [:streams 0 :send-queue] [{:tsn 1 :sent? true :retries 0 :chunk {:type :data :payload (byte-array 10)}}])
                     (assoc-in [:timers :sctp/t3-rtx] {:expires-at 1000 :delay 1000}))
          res (core/handle-timeout state2 :sctp/t3-rtx 1500)
          new-state (:new-state res)]
      (is (= 1 (get-in new-state [:metrics :retransmissions] 0))))))

(deftest retransmission-metrics-are-set-for-fast-retransmit-test
  (testing "Retransmission Metrics Are Set For Fast Retransmit"
    (let [init-state (core/create-connection {} true)
          state1 (assoc init-state :state :established)
          ;; Send 4 chunks, chunk 1 is lost. We will receive SACK for chunk 2, 3, 4
          ;; indicating duplicate TSNs and gaps.
          chunk1 {:tsn 1 :sent? true :retries 0 :missing-reports 0 :chunk {:type :data :payload (byte-array 10)}}
          chunk2 {:tsn 2 :sent? true :retries 0 :missing-reports 0 :chunk {:type :data :payload (byte-array 10)}}
          chunk3 {:tsn 3 :sent? true :retries 0 :missing-reports 0 :chunk {:type :data :payload (byte-array 10)}}
          chunk4 {:tsn 4 :sent? true :retries 0 :missing-reports 0 :chunk {:type :data :payload (byte-array 10)}}
          state2 (-> state1
                     (assoc-in [:streams 0 :send-queue] [chunk1 chunk2 chunk3 chunk4])
                     (assoc :next-tsn 5)
                     (assoc-in [:timers :sctp/t3-rtx] {:expires-at 1000 :delay 1000}))

          sack1 {:type :sack :cum-tsn-ack 0 :gap-blocks [[2 2]] :duplicate-tsns []}
          res1 (@#'chunks/process-chunk state2 sack1 nil 0)

          sack2 {:type :sack :cum-tsn-ack 0 :gap-blocks [[2 3]] :duplicate-tsns []}
          res2 (@#'chunks/process-chunk (:next-state res1) sack2 nil 0)

          sack3 {:type :sack :cum-tsn-ack 0 :gap-blocks [[2 4]] :duplicate-tsns []}
          res3 (@#'chunks/process-chunk (:next-state res2) sack3 nil 0)]

      (is (= 1 (get-in (:next-state res3) [:metrics :retransmissions] 0)))
      (is (= false (:sent? (first (get-in (:next-state res3) [:streams 0 :send-queue])))))
      (is (= 1 (:retries (first (get-in (:next-state res3) [:streams 0 :send-queue]))))))))
