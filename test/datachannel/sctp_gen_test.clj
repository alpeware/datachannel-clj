(ns datachannel.sctp-gen-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datachannel.core :as dc]))

(def gen-op
  (gen/one-of
   [(gen/tuple (gen/return :advance-time) (gen/choose 10 1000))
    (gen/tuple (gen/return :receive-packet)
               (gen/fmap byte-array (gen/vector gen/byte 1 100)))
    (gen/tuple (gen/return :send-data)
               (gen/fmap byte-array (gen/vector gen/byte 1 15000))
               (gen/choose 0 5)
               (gen/elements [:webrtc/string :webrtc/binary]))]))

(defn valid-unacked-data? [state]
  (let [expected (reduce + (map (fn [s] (reduce + (map #(+ 16 (if-let [p (:payload (:chunk %))] (alength ^bytes p) 0)) (:send-queue s)))) (vals (:streams state))))]
    (= (get-in state [:metrics :unacked-data] 0) expected)))

(defn queue-size [q]
  (reduce + (map #(if-let [p (:payload (:chunk %))] (alength ^bytes p) 0) q)))

(defn valid-queue-limits? [state]
  (let [max-q (get state :max-queue-size)]
    (if max-q
      (every? (fn [s]
                (<= (dc/get-buffered-amount state s) max-q))
              (keys (:streams state)))
      true)))

(defn valid-congestion-metrics? [state]
  (and (>= (get state :flight-size 0) 0)
       (>= (get state :cwnd 0) 0)))

(defn valid-buffer-amounts? [state]
  (every? (fn [s]
            (= (dc/get-buffered-amount state s)
               (queue-size (get-in state [:streams s :send-queue] []))))
          (keys (:streams state))))

(defn valid-delayed-sack-state? [state]
  (let [unacked (get state :unacked-data-chunks 0)
        has-timer? (contains? (:timers state) :sctp/t-delayed-sack)]
    (and (>= unacked 0)
         (<= unacked 1)
         (= (= unacked 1) has-timer?))))

(defn valid-buffered-amount-low-events? [old-buffered new-buffered threshold app-events]
  (every? (fn [s]
            (let [o (get old-buffered s 0)
                  n (get new-buffered s 0)
                  crossed? (and (> o threshold) (<= n threshold))
                  evt-present? (some #(and (= (:type %) :on-buffered-amount-low)
                                           (= (:stream-id %) s))
                                     app-events)]
              (= crossed? (boolean evt-present?))))
          (keys new-buffered)))

(defn valid-total-buffered-low-events? [old-total new-total threshold app-events]
  (let [crossed-total? (and (> old-total threshold) (<= new-total threshold))
        total-evt-present? (some #(= (:type %) :on-total-buffered-amount-low) app-events)]
    (= crossed-total? (boolean total-evt-present?))))

(defn valid-no-spurious-ack? [op-type timers-triggered? next-network-out]
  (let [spurious-ack? (and (= op-type :advance-time)
                           (not timers-triggered?)
                           (seq next-network-out))]
    (not spurious-ack?)))

(defn valid-metrics-increase? [op-type next-network-out old-state new-state]
  (let [old-tx (get-in old-state [:metrics :tx-packets] 0)
        new-tx (get-in new-state [:metrics :tx-packets] 0)
        old-rx (get-in old-state [:metrics :rx-packets] 0)
        new-rx (get-in new-state [:metrics :rx-packets] 0)
        net-out-size (count next-network-out)]
    (and (>= new-tx old-tx)
         (>= new-rx old-rx)
         (if (= op-type :send-data)
           (= new-tx (+ old-tx net-out-size))
           (>= new-tx old-tx)))))

(defn valid-max-burst? [state next-network-out]
  (let [max-burst (get state :max-burst 4)
        data-pkts (count (filter (fn [pkt] (and (map? pkt) (some #(= (:type %) :data) (:chunks pkt)))) next-network-out))]
    (<= data-pkts max-burst)))


(defn setup-established-state []
  (let [init-state (dc/create-connection {:max-queue-size 50000} true)
        connect-res (dc/handle-event init-state {:type :connect} 0)
        state1 (:new-state connect-res)
        init-packet (first (:network-out connect-res))

        ;; Simulate remote sending INIT-ACK
        init-ack-packet {:src-port (:dst-port init-packet)
                         :dst-port (:src-port init-packet)
                         :verification-tag (:init-tag (first (:chunks init-packet)))
                         :chunks [{:type :init-ack
                                   :init-tag 2222
                                   :a-rwnd 100000
                                   :outbound-streams 10
                                   :inbound-streams 10
                                   :initial-tsn 2000
                                   :params {}}]}
        res-init-ack (@#'dc/handle-sctp-packet state1 init-ack-packet 0)
        state2 (:new-state res-init-ack)
        cookie-echo-packet (first (:network-out res-init-ack))

        ;; Simulate remote sending COOKIE-ACK
        cookie-ack-packet {:src-port (:dst-port cookie-echo-packet)
                           :dst-port (:src-port cookie-echo-packet)
                           :verification-tag (:local-ver-tag state2)
                           :chunks [{:type :cookie-ack}]}
        res-cookie-ack (@#'dc/handle-sctp-packet state2 cookie-ack-packet 0)
        state3 (:new-state res-cookie-ack)]
    state3))

(def prop-sctp-state-machine-invariants
  (prop/for-all [ops (gen/vector gen-op 10 100)]
                (let [init-state (setup-established-state)]
                  (loop [state init-state
                         ops ops
                         now-ms 0]
                    (if (empty? ops)
                      (let [flight-size (get state :flight-size 0)
                            cwnd (get state :cwnd 0)]
                        (and (>= flight-size 0)
                             (>= cwnd 0)))
                      (let [[op-type arg1 arg2 arg3] (first ops)
                            old-buffered (into {} (map (fn [s] [s (dc/get-buffered-amount state s)]) (keys (:streams state))))
                            [next-state next-now app-events next-network-out]
                            (case op-type
                              :advance-time
                              (let [new-now (+ now-ms arg1)
                                    res (reduce (fn [acc [timer-id t]]
                                                  (if (<= (:expires-at t) new-now)
                                                    (let [r (dc/handle-timeout (:s acc) timer-id new-now)]
                                                      {:s (:new-state r)
                                                       :evts (into (:evts acc) (:app-events r []))
                                                       :network-out (into (:network-out acc) (:network-out r []))})
                                                    acc))
                                                {:s state :evts [] :network-out []}
                                                (:timers state))]
                                [(:s res) new-now (:evts res) (:network-out res)])
                              :receive-packet
                              (let [res (dc/handle-receive state arg1 now-ms nil)]
                                [(:new-state res) now-ms (:app-events res) (:network-out res)])
                              :send-data
                              (try
                                (let [res (dc/send-data state arg1 arg2 arg3 now-ms)]
                                  [(:new-state res) now-ms (:app-events res) (:network-out res)])
                                (catch clojure.lang.ExceptionInfo e
                                  ;; Expect too large or empty message exceptions
                                  (if (or (= (:type (ex-data e)) :empty-payload)
                                          (= (:type (ex-data e)) :too-large)
                                          (= (:type (ex-data e)) :queue-limit-reached))
                                    [state now-ms [] []]
                                    (throw e)))))

                            new-buffered (into {} (map (fn [s] [s (dc/get-buffered-amount next-state s)]) (keys (:streams next-state))))
                            low-threshold (get next-state :buffered-amount-low-threshold 0)
                            old-total (reduce + (vals old-buffered))
                            new-total (reduce + (vals new-buffered))
                            total-low-threshold (get next-state :total-buffered-amount-low-threshold 0)

                            ;; Invariant: Advance Time Does Not Trigger Spurious Ack
                            ;; Spurious ack here means: if we just advance time and NO timers were triggered (or if it's purely advancing time with no timers at all),
                            ;; we shouldn't be randomly spitting out network packets like SACKs or anything else.
                            timers-triggered? (if (= op-type :advance-time)
                                                (some (fn [[_ t]] (<= (:expires-at t) (+ now-ms arg1))) (:timers state))
                                                true)

                            valid? (and (valid-queue-limits? next-state)
                                        (valid-congestion-metrics? next-state)
                                        (valid-buffer-amounts? next-state)
                                        (valid-buffered-amount-low-events? old-buffered new-buffered low-threshold app-events)
                                        (valid-total-buffered-low-events? old-total new-total total-low-threshold app-events)
                                        (valid-unacked-data? next-state)
                                        (valid-no-spurious-ack? op-type timers-triggered? next-network-out)
                                        (valid-metrics-increase? op-type next-network-out state next-state)
                                        (valid-max-burst? next-state next-network-out)
                                        (valid-delayed-sack-state? next-state))]
                        (if-not valid?
                          false
                          (recur next-state (rest ops) next-now))))))))

(defspec test-sctp-state-machine-invariants (Integer/parseInt (or (System/getenv "FUZZ_ITERS") "100")) prop-sctp-state-machine-invariants)
