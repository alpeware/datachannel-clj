(ns datachannel.sctp-receive-queue-bound-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.chunks :as chunks]
            [datachannel.core :as dc]))

(deftest test-receive-queue-bound
  (testing "SCTP DATA chunks exceeding max-receive-queue-size are rejected or ignored"
    (let [init-state (assoc (dc/create-connection {} true) :state :established :max-receive-queue-size 1000)
          packet {:src-port 5000 :dst-port 5000 :verification-tag 0}
          ;; Fill the queue up to exactly the limit
          chunk1 {:type :data, :tsn 1, :stream-id 0, :seq-num 0, :protocol :webrtc/binary, :payload (byte-array 500)}
          chunk2 {:type :data, :tsn 2, :stream-id 0, :seq-num 0, :protocol :webrtc/binary, :payload (byte-array 500)}
          ;; This chunk should be dropped because it exceeds 1000 bytes
          chunk3 {:type :data, :tsn 3, :stream-id 0, :seq-num 0, :protocol :webrtc/binary, :payload (byte-array 500)}

          res1 (chunks/process-chunk init-state chunk1 packet 0)
          res2 (chunks/process-chunk (:next-state res1) chunk2 packet 0)
          res3 (chunks/process-chunk (:next-state res2) chunk3 packet 0)

          final-queue (get-in (:next-state res3) [:streams 0 :recv-queue] [])
          final-size (reduce + (map #(alength ^bytes (:payload %)) final-queue))]

      (is (= 2 (count final-queue)))
      (is (= 1000 final-size))
      (is (= 1 (:tsn (first final-queue))))
      (is (= 2 (:tsn (second final-queue)))))))
