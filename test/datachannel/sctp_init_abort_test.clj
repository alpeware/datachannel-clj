(ns datachannel.sctp-init-abort-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest resending-init-too-many-times-aborts-test
  (testing "Resending Init Too Many Times Aborts"
    (let [initial-state {:state :closed
                         :local-ver-tag 12345
                         :initial-tsn 0
                         :timers {}}
          now 1000000

          ;; 1. Initiate connection
          res1 (core/handle-event initial-state {:type :connect} now)
          new-state1 (:new-state res1)
          network-out1 (:network-out res1)
          _ (is (= :cookie-wait (:state new-state1)) "State should transition to cookie-wait")
          _ (is (= 1 (count network-out1)) "Should generate one outgoing packet")
          _ (is (= :init (-> network-out1 first :chunks first :type)) "Should send INIT chunk")

          timer (get-in new-state1 [:timers :sctp/t1-init])
          _ (is timer "Should setup t1-init timer")
          _ (is (= 0 (:retries timer)) "Initial retries should be 0")
          _ (is (= 3000 (:delay timer)) "Initial delay should be 3000ms")
          _ (is (= (+ now 3000) (:expires-at timer)) "Timer should expire at now + delay")]

      ;; Simulate 8 retransmissions
      (loop [state new-state1
             _current-time now
             retries 0]
        (if (< retries 8)
              ;; Retransmit
          (let [expired-time (:expires-at (get-in state [:timers :sctp/t1-init]))
                {:keys [new-state network-out]} (core/handle-timeout state :sctp/t1-init expired-time)]
            (is (= 1 (count network-out)) "Should generate one packet for retry")
            (is (= :init (-> network-out first :chunks first :type)) "Should send INIT chunk again")
            (is (= (inc retries) (:retries (get-in new-state [:timers :sctp/t1-init]))) "Should increment retries")
            (recur new-state expired-time (inc retries)))

            ;; Final timeout (Abort)
          (let [expired-time (:expires-at (get-in state [:timers :sctp/t1-init]))
                {:keys [new-state app-events _network-out]} (core/handle-timeout state :sctp/t1-init expired-time)]
            (is (= :closed (:state new-state)) "State should transition to closed")
            (is (nil? (get-in new-state [:timers :sctp/t1-init])) "Timer should be removed")
            (is (= 1 (count app-events)) "Should generate one event for abort")
            (is (= :on-error (:type (first app-events))) "Event should be on-error")
            (is (= :max-retransmissions (:cause (first app-events))) "Error cause should be max-retransmissions")))))))
