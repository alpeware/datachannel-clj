(ns datachannel.sctp-shutdown-connection-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest shutdown-connection-without-pending-data-test
  (testing "Shutdown Connection Without Pending Data"
    (let [now 1000
          state {:state :established
                 :remote-ver-tag 2222
                 :local-ver-tag 1111
                 :local-port 5000
                 :remote-port 5001
                 :next-tsn 1000
                 :ssn 0
                 :timers {}
                 :streams {}}
          res (core/handle-event state {:type :shutdown} now)
          new-state (:new-state res)
          net-out (:network-out res)
          app-events (:app-events res)]

      (is (= :shutdown-sent (:state new-state)) "State should transition to :shutdown-sent")
      (is (= 1 (count net-out)) "Should output one packet")
      (let [pkt (first net-out)]
        (is (= :shutdown (get-in pkt [:chunks 0 :type])) "Packet should contain SHUTDOWN chunk")
        (is (= 5000 (:src-port pkt)) "Packet should have correct src port")
        (is (= 5001 (:dst-port pkt)) "Packet should have correct dst port"))
      (is (contains? (:timers new-state) :sctp/t2-shutdown) "Should start :sctp/t2-shutdown timer")
      (is (= [{:type :on-closing}] app-events) "Should not generate app events"))))

(deftest shutdown-connection-with-pending-data-test
  (testing "Shutdown Connection With Pending Data"
    (let [now 1000
          state {:state :established
                 :remote-ver-tag 2222
                 :local-ver-tag 1111
                 :local-port 5000
                 :remote-port 5001
                 :next-tsn 1000
                 :ssn 0
                 :timers {}
                 :streams {0 {:send-queue [{:tsn 1000 :chunk {:type :data} :sent-at now :retries 0 :sent? true}]}}}
          res (core/handle-event state {:type :shutdown} now)
          new-state (:new-state res)
          net-out (:network-out res)
          app-events (:app-events res)]

      (is (= :shutdown-pending (:state new-state)) "State should transition to :shutdown-pending")
      (is (empty? net-out) "Should not output packets immediately")
      (is (not (contains? (:timers new-state) :sctp/t2-shutdown)) "Should not start :sctp/t2-shutdown timer")
      (is (= [{:type :on-closing}] app-events) "Should not generate app events"))))
