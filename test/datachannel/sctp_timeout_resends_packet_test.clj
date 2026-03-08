(ns datachannel.sctp-timeout-resends-packet-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest timeout-resends-packet-test
  (testing "Timeout Resends Packet"
    (let [now-ms 1000
          payload (.getBytes "Hello" "UTF-8")
          packet {:src-port 5000 :dst-port 5001 :verification-tag 2222
                  :chunks [{:type :data
                            :tsn 1000
                            :stream-id 0
                            :ssn 0
                            :protocol :webrtc/string
                            :payload payload}]}
          state {:state :established
                 :remote-ver-tag 2222
                 :local-ver-tag 1111
                 :src-port 5000
                 :dst-port 5001
                 :next-tsn 1001
                 :ssn 1
                 :tx-queue [{:tsn 1000 :packet packet :sent-at now-ms :retries 0}]
                 :timers {:t3-rtx {:expires-at (+ now-ms 1000) :delay 1000}}
                 :max-retransmissions 10
                 :metrics {:retransmissions 0}}

          ;; Trigger t3-rtx timeout
          next-now (+ now-ms 1000)
          res (core/handle-timeout state :t3-rtx next-now)
          new-state (:new-state res)
          net-out (:network-out res)
          app-events (:app-events res)]

      (is (= :established (:state new-state)) "State should remain :established")
      (is (= 1 (count net-out)) "Should retransmit one packet")
      (let [out-pkt (first net-out)]
        (is (= :data (get-in out-pkt [:chunks 0 :type])) "Packet should contain DATA chunk")
        (is (= "Hello" (String. ^bytes (get-in out-pkt [:chunks 0 :payload]) "UTF-8")) "Payload should match")
        (is (= 2222 (:verification-tag out-pkt)) "Verification tag should match remote ver tag"))

      (is (= 1 (get-in new-state [:metrics :retransmissions])) "Retransmission metric should be incremented")
      (is (contains? (:timers new-state) :t3-rtx) "t3-rtx timer should be restarted")
      (is (= (+ next-now 2000) (get-in new-state [:timers :t3-rtx :expires-at])) "Timer should back off exponentially (delay * 2)")
      (is (= 2000 (get-in new-state [:timers :t3-rtx :delay])) "Timer delay should be doubled")
      (is (= 1 (get-in new-state [:tx-queue 0 :retries])) "Packet retries should be incremented")
      (is (empty? app-events) "Should not generate app events"))))
