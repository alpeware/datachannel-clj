(ns datachannel.sctp-timeout-resends-packet-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest timeout-resends-packet-test
  (testing "Timeout Resends Packet"
    (let [state-a (atom {:remote-tsn 0 :remote-ver-tag 2222 :next-tsn 1000 :ssn 0 :state :established :timers {}})
          state-z (atom {:remote-tsn 0 :remote-ver-tag 1111 :next-tsn 2000 :ssn 0 :state :established :timers {}})
          out-a (java.util.concurrent.LinkedBlockingQueue.)
          out-z (java.util.concurrent.LinkedBlockingQueue.)
          conn-a {:state state-a :sctp-out out-a :on-open (atom nil) :on-close (atom nil) :selector nil :on-message (atom nil) :on-data (atom nil)}
          conn-z {:state state-z :sctp-out out-z :on-open (atom nil) :on-close (atom nil) :selector nil :on-message (atom nil) :on-data (atom nil)}
          handle-sctp-packet #'core/handle-sctp-packet]

      ;; Z sends data
      (core/send-data conn-z (.getBytes "hello") 1 :webrtc/string)
      (let [data-packet (.poll out-z)
            timer-z (get-in @state-z [:timers :t3-rtx])]
        (is data-packet "Z should have sent a data packet")
        (is (= :data (:type (first (:chunks data-packet)))))
        (is timer-z "Z should have started T3-RTX timer")

        ;; Simulate packet loss: Z's data packet is discarded.

        ;; Advance time to trigger T3-RTX timeout on Z
        (let [now (+ (:expires-at timer-z) 10)
              {:keys [new-state effects]} (core/handle-timeout @state-z :t3-rtx now)]
          (reset! state-z new-state)
          (doseq [effect effects]
            (when (= (:type effect) :send-packet)
              (.offer out-z (:packet effect)))))

        ;; Z should have re-queued the data packet
        (let [retransmitted-data-packet (.poll out-z)]
          (is retransmitted-data-packet "Z should have retransmitted the data packet due to timeout")
          (is (= :data (:type (first (:chunks retransmitted-data-packet)))))

          ;; A receives the retransmitted data packet and replies with SACK
          (handle-sctp-packet retransmitted-data-packet conn-a)
          (let [sack-packet (.poll out-a)]
            (is sack-packet "A should have sent a SACK")
            (is (= :sack (:type (first (:chunks sack-packet)))))

            ;; Z receives the SACK
            (handle-sctp-packet sack-packet conn-z)

            ;; Z should have stopped T3-RTX timer
            (is (nil? (get-in @state-z [:timers :t3-rtx])) "Z should have stopped T3-RTX timer after receiving SACK")))))))
