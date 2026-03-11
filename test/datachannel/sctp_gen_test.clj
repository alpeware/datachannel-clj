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
               (gen/fmap byte-array (gen/vector gen/byte 1 1500))
               (gen/choose 0 5)
               (gen/elements [:webrtc/string :webrtc/binary]))]))

(defn queue-size [q]
  (reduce + (map #(if-let [p (:payload (:chunk %))] (alength ^bytes p) 0) q)))

(defn setup-established-state []
  (let [init-state (dc/create-connection {} true)
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
                            [next-state next-now app-events]
                            (case op-type
                              :advance-time
                              (let [new-now (+ now-ms arg1)
                                    res (reduce (fn [acc [timer-id t]]
                                                  (if (<= (:expires-at t) new-now)
                                                    (let [r (dc/handle-timeout (:s acc) timer-id new-now)]
                                                      {:s (:new-state r)
                                                       :evts (into (:evts acc) (:app-events r []))})
                                                    acc))
                                                {:s state :evts []}
                                                (:timers state))]
                                [(:s res) new-now (:evts res)])
                              :receive-packet
                              (let [res (dc/handle-receive state arg1 now-ms nil)]
                                [(:new-state res) now-ms (:app-events res)])
                              :send-data
                              (try
                                (let [res (dc/send-data state arg1 arg2 arg3 now-ms)]
                                  [(:new-state res) now-ms (:app-events res)])
                                (catch clojure.lang.ExceptionInfo e
                                  ;; Expect too large or empty message exceptions
                                  (if (or (= (:type (ex-data e)) :empty-payload)
                                          (= (:type (ex-data e)) :too-large))
                                    [state now-ms []]
                                    (throw e)))))

                            new-buffered (into {} (map (fn [s] [s (dc/get-buffered-amount next-state s)]) (keys (:streams next-state))))
                            low-threshold (get next-state :buffered-amount-low-threshold 0)
                            old-total (reduce + (vals old-buffered))
                            new-total (reduce + (vals new-buffered))
                            total-low-threshold (get next-state :total-buffered-amount-low-threshold 0)]
                        (if (or (< (get next-state :flight-size 0) 0)
                                (< (get next-state :cwnd 0) 0)
                                (not (every? (fn [s]
                                               (= (dc/get-buffered-amount next-state s)
                                                  (queue-size (get-in next-state [:streams s :send-queue] []))))
                                             (keys (:streams next-state))))
                                ;; Check buffered-amount-low events
                                (not (every? (fn [s]
                                               (let [o (get old-buffered s 0)
                                                     n (get new-buffered s 0)
                                                     crossed? (and (> o low-threshold) (<= n low-threshold))
                                                     evt-present? (some #(and (= (:type %) :on-buffered-amount-low)
                                                                              (= (:stream-id %) s))
                                                                        app-events)]
                                                 (= crossed? (boolean evt-present?))))
                                             (keys new-buffered)))
                                ;; Check total-buffered-amount-low events
                                (not (let [crossed-total? (and (> old-total total-low-threshold) (<= new-total total-low-threshold))
                                           total-evt-present? (some #(= (:type %) :on-total-buffered-amount-low) app-events)]
                                       (= crossed-total? (boolean total-evt-present?)))))
                          false
                          (recur next-state (rest ops) next-now))))))))

(defspec test-sctp-state-machine-invariants (Integer/parseInt (or (System/getenv "FUZZ_ITERS") "100")) prop-sctp-state-machine-invariants)
