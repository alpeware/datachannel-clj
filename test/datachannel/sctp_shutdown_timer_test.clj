(ns datachannel.sctp-shutdown-timer-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest shutdown-timer-expires-too-many-time-closes-connection-test
  (testing "Shutdown Timer Expires Too Many Time Closes Connection"
    (let [state-a (atom {:remote-tsn 0 :remote-ver-tag 2222 :next-tsn 1000 :ssn 0 :state :established :timers {}})
          out-a (java.util.concurrent.LinkedBlockingQueue.)
          on-error-called (atom false)
          conn-a {:state state-a :sctp-out out-a :on-open (atom nil) :on-error (atom (fn [err] (reset! on-error-called true))) :selector nil}
          now 1000000]

      ;; Simulate sending SHUTDOWN
      ;; A initiates SHUTDOWN locally
      (reset! state-a (assoc @state-a :state :shutdown-pending))

      ;; To transition to shutdown-sent, A needs to process a cookie-ack or just send SHUTDOWN directly when user closes.
      ;; In core.clj, :shutdown-pending transitions to :shutdown-sent upon receiving :cookie-ack. Wait, if it's already established, how does it send SHUTDOWN?
      ;; core.clj currently doesn't have an explicit user-initiated close() that triggers SHUTDOWN immediately if established.
      ;; Let's simulate what handle-sctp-packet does for :cookie-ack when :shutdown-pending

      (let [shutdown-packet {:src-port 5000 :dst-port 5001 :verification-tag 2222 :chunks [{:type :shutdown}]}]
        (swap! state-a assoc :state :shutdown-sent)
        (swap! state-a assoc-in [:timers :t2-shutdown] {:expires-at (+ now 1000) :delay 1000 :retries 0 :packet shutdown-packet})
        (.offer out-a shutdown-packet))

      (is (= :shutdown-sent (:state @state-a)))
      (is (.poll out-a)) ;; Consume first SHUTDOWN

      ;; Expire timer 8 times
      (loop [current-now now
             i 0]
        (if (< i 8)
          (let [timer (get-in @state-a [:timers :t2-shutdown])
                next-now (:expires-at timer)
                {:keys [new-state network-out]} (core/handle-timeout @state-a :t2-shutdown next-now)]
            (reset! state-a new-state)
            (doseq [eff network-out]
              (if (= (:type eff) :send-packet)
                (.offer out-a (:packet eff))))
            (is (.poll out-a)) ;; Consume retransmitted SHUTDOWN
            (recur next-now (inc i)))
          ;; 9th expiry should close
          (let [timer (get-in @state-a [:timers :t2-shutdown])
                next-now (:expires-at timer)
                {:keys [new-state network-out]} (core/handle-timeout @state-a :t2-shutdown next-now)]
            (reset! state-a new-state)
            (doseq [eff network-out]
              (if (= (:type eff) :on-error)
                (@(:on-error conn-a) (:cause eff))
                (if (= (:type eff) :send-packet)
                  (.offer out-a (:packet eff)))))

            ;; Check if it sent an ABORT chunk
            (let [abort-pkt (.poll out-a)]
              (is abort-pkt)
              (is (= :abort (:type (first (:chunks abort-pkt)))))))))

      (is (= :closed (:state @state-a)))
      (is @on-error-called))))
