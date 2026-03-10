(ns datachannel.sctp-expect-heartbeat-to-be-sent-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest expect-heartbeat-to-be-sent-test
  (testing "Expect Heartbeat To Be Sent"
    (let [now 1000
          state {:state :established
                 :remote-ver-tag 2222
                 :local-ver-tag 1111
                 :src-port 5000
                 :dst-port 5001
                 :next-tsn 1000
                 :remote-tsn 0
                 :ssn 0
                 :timers {:sctp/t-heartbeat {:expires (+ now 100)}}}

          ;; Trigger the timeout
          res (core/handle-timeout state :sctp/t-heartbeat (+ now 150))
          out-pkts (:network-out res)
          heartbeat-pkt (first out-pkts)]

      (is (= 1 (count out-pkts)) "Should send exactly one packet in response")
      (is (= :heartbeat (:type (first (:chunks heartbeat-pkt)))) "The chunk type should be heartbeat")
      (is (contains? (:timers (:new-state res)) :sctp/t-heartbeat-rtx) "Should start a retransmission timer for the heartbeat"))))
