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

(def prop-sctp-state-machine-invariants
  (prop/for-all [ops (gen/vector gen-op 10 100)]
                (let [init-state (-> (dc/create-connection {} true)
                                     (assoc :state :established
                                            :cwnd 100000
                                            :remote-ver-tag 123
                                            :flight-size 0))]
                  (loop [state init-state
                         ops ops
                         now-ms 0]
                    (if (empty? ops)
                      (let [flight-size (get state :flight-size 0)
                            cwnd (get state :cwnd 0)]
                        (and (>= flight-size 0)
                             (>= cwnd 0)))
                      (let [[op-type arg1 arg2 arg3] (first ops)
                            [next-state next-now]
                            (case op-type
                              :advance-time
                              [(let [new-now (+ now-ms arg1)]
                                 (reduce (fn [s [timer-id t]]
                                           (if (<= (:expires-at t) new-now)
                                             (:new-state (dc/handle-timeout s timer-id new-now))
                                             s))
                                         state
                                         (:timers state)))
                               (+ now-ms arg1)]
                              :receive-packet
                              [(:new-state (dc/handle-receive state arg1 now-ms nil))
                               now-ms]
                              :send-data
                              [(try
                                 (let [res (dc/send-data state arg1 arg2 arg3 now-ms)]
                                   (:new-state res))
                                 (catch clojure.lang.ExceptionInfo e
                                   ;; Expect too large or empty message exceptions
                                   (if (or (= (:type (ex-data e)) :empty-payload)
                                           (= (:type (ex-data e)) :too-large))
                                     state
                                     (throw e))))
                               now-ms])]
                        (if (or (< (get next-state :flight-size 0) 0)
                                (< (get next-state :cwnd 0) 0))
                          false
                          (recur next-state (rest ops) next-now))))))))

(defspec test-sctp-state-machine-invariants 100 prop-sctp-state-machine-invariants)
