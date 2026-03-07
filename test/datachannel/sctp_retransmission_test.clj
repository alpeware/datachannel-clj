(ns datachannel.sctp-retransmission-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest close-connection-after-first-failed-transmission-test
  (testing "Close connection after first failed transmission"
    (let [state-atom (atom {:state :established
                            :remote-ver-tag 1234
                            :local-ver-tag 5678
                            :next-tsn 1000
                            :ssn 0
                            :timers {}
                            :tx-queue []
                            :max-retransmissions 0})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          conn {:state state-atom
                :sctp-out out-queue
                :on-error (atom (fn [cause] nil))}
          payload (.getBytes "test payload")
          now (System/currentTimeMillis)]

      (core/send-data conn payload 0 :webrtc/string)

      (let [data-packet (.poll out-queue)]
        (is data-packet "Should send data packet")
        (is (= :data (:type (first (:chunks data-packet)))) "Chunk should be data"))

      ;; Let the T3-rtx expire once. Since max-retransmissions is 0,
      ;; it should immediately abort instead of retransmitting.
      (let [timer-expire-time (+ now 1000)
            {:keys [new-state effects]} (core/handle-timeout @state-atom :t3-rtx timer-expire-time)]
        (reset! state-atom new-state)
        (is (= :closed (:state @state-atom)) "State should transition to closed")

        ;; Verify effects
        (let [abort-effect (first (filter #(= (:type %) :send-packet) effects))
              error-effect (first (filter #(= (:type %) :on-error) effects))]
          (is abort-effect "Should emit an effect to send abort packet")
          (is (= :abort (:type (first (:chunks (:packet abort-effect))))) "Should be an abort packet")
          (is error-effect "Should emit an on-error effect")
          (is (= :max-retransmissions (:cause error-effect)) "Error cause should be max-retransmissions"))))))

(deftest close-connection-after-one-failed-retransmission-test
  (testing "Close connection after one failed retransmission"
    (let [state-atom (atom {:state :established
                            :remote-ver-tag 1234
                            :local-ver-tag 5678
                            :next-tsn 1000
                            :ssn 0
                            :timers {}
                            :tx-queue []
                            :max-retransmissions 1})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          conn {:state state-atom
                :sctp-out out-queue
                :on-error (atom (fn [cause] nil))}
          payload (.getBytes "test payload")
          now (System/currentTimeMillis)]

      (core/send-data conn payload 0 :webrtc/string)

      (let [data-packet (.poll out-queue)]
        (is data-packet "Should send data packet")
        (is (= :data (:type (first (:chunks data-packet)))) "Chunk should be data"))

      ;; Expire T3-rtx first time. Since max-retransmissions is 1, it should retransmit.
      (let [timer-expire-time (+ now 1000)
            {:keys [new-state effects]} (core/handle-timeout @state-atom :t3-rtx timer-expire-time)]
        (reset! state-atom new-state)
        (is (= :established (:state @state-atom)) "State should remain established")

        (let [rtx-effect (first (filter #(= (:type %) :send-packet) effects))]
          (is rtx-effect "Should emit an effect to retransmit data packet")
          (is (= :data (:type (first (:chunks (:packet rtx-effect))))) "Should retransmit data")))

      ;; Expire T3-rtx second time. Now retries == max-retransmissions == 1, so it should abort.
      (let [timer-expire-time (+ now 1000 2000)
            {:keys [new-state effects]} (core/handle-timeout @state-atom :t3-rtx timer-expire-time)]
        (reset! state-atom new-state)
        (is (= :closed (:state @state-atom)) "State should transition to closed")

        (let [abort-effect (first (filter #(= (:type %) :send-packet) effects))]
          (is abort-effect "Should emit an effect to send abort packet")
          (is (= :abort (:type (first (:chunks (:packet abort-effect))))) "Should be an abort packet"))))))

(deftest close-connection-after-too-many-retransmissions-test
  (testing "Close connection after too many retransmissions"
    (let [max-rtx 5
          state-atom (atom {:state :established
                            :remote-ver-tag 1234
                            :local-ver-tag 5678
                            :next-tsn 1000
                            :ssn 0
                            :timers {}
                            :tx-queue []
                            :max-retransmissions max-rtx})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          conn {:state state-atom
                :sctp-out out-queue
                :on-error (atom (fn [cause] nil))}
          payload (.getBytes "test payload")
          now (System/currentTimeMillis)]

      (core/send-data conn payload 0 :webrtc/string)
      (.poll out-queue) ;; consume initial send

      (loop [i 0
             current-time (+ now 1000)]
        (if (< i max-rtx)
          (let [{:keys [new-state effects]} (core/handle-timeout @state-atom :t3-rtx current-time)]
            (reset! state-atom new-state)
            (is (= :established (:state @state-atom)) "State should remain established during retransmissions")
            (let [rtx-effect (first (filter #(= (:type %) :send-packet) effects))]
              (is rtx-effect "Should emit an effect to retransmit data packet")
              (is (= :data (:type (first (:chunks (:packet rtx-effect))))) "Should retransmit data"))
            (recur (inc i) (+ current-time (* 1000 (Math/pow 2 (inc i))))))

          ;; Final timeout
          (let [{:keys [new-state effects]} (core/handle-timeout @state-atom :t3-rtx current-time)]
            (reset! state-atom new-state)
            (is (= :closed (:state @state-atom)) "State should transition to closed")
            (let [abort-effect (first (filter #(= (:type %) :send-packet) effects))]
              (is abort-effect "Should emit an effect to send abort packet")
              (is (= :abort (:type (first (:chunks (:packet abort-effect))))) "Should be an abort packet"))))))))
