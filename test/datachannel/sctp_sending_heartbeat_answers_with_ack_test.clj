(ns datachannel.sctp-sending-heartbeat-answers-with-ack-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest sending-heartbeat-answers-with-ack-test
  (testing "Sending Heartbeat Answers With Ack"
    (let [now 1000
          state {:state :established
                 :remote-ver-tag 2222
                 :local-ver-tag 1111
                 :src-port 5000
                 :dst-port 5001
                 :next-tsn 1000
                 :remote-tsn 0
                 :ssn 0
                 :heartbeat-interval 1000
                 :heartbeat-error-count 0
                 :rto-initial 1000
                 :max-retransmissions 10}

          ;; Received heartbeat from remote peer
          heartbeat-packet {:src-port 5001 :dst-port 5000 :verification-tag 1111
                            :chunks [{:type :heartbeat :params [{:type :heartbeat-info :info (byte-array [1 2 3 4])}]}]}

          res (core/handle-sctp-packet state heartbeat-packet now)
          out-pkts (:network-out res)
          ack-pkt (first out-pkts)]

      (is (= 1 (count out-pkts)) "Should send exactly one packet in response")
      (is (= :heartbeat-ack (:type (first (:chunks ack-pkt)))) "The chunk type should be heartbeat-ack")
      (is (= (seq [1 2 3 4])
             (seq (:info (first (-> ack-pkt :chunks first :params)))))
          "The heartbeat-ack should contain the same parameters as the heartbeat"))))
