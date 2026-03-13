(ns datachannel.chaos-fuzz-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datachannel.core :as dc])
  (:import [java.nio ByteBuffer]))

(defn- bb->bytes [^ByteBuffer bb]
  (let [buf (byte-array (.remaining bb))]
    (.get (.duplicate bb) buf)
    buf))

(defn apply-chaos [packets drop-rate dup-rate reorder? rand-seed]
  (let [rng (java.util.Random. rand-seed)
        ;; 1. Drop
        after-drop (filter (fn [_] (> (.nextDouble rng) drop-rate)) packets)
        ;; 2. Duplicate
        after-dup (mapcat (fn [p] (if (< (.nextDouble rng) dup-rate) [p p] [p])) after-drop)
        ;; 3. Reorder
        after-reorder (if reorder?
                        (let [l (java.util.ArrayList. ^java.util.Collection after-dup)]
                          (java.util.Collections/shuffle l rng)
                          (vec l))
                        after-dup)]
    after-reorder))

(defn pump-chaos-network [state-a state-b condition? max-iterations start-time drop-rate dup-rate reorder? rand-seed]
  (let [rng (java.util.Random. rand-seed)]
    (loop [a state-a
           b state-b
           i 0]
      (let [now-ms (+ start-time (* i 100))
            a-events (:app-events a [])
            b-events (:app-events b [])]
        (if (or (>= i max-iterations)
                (not (condition? a b)))
          [a b i]
          (let [a-bytes (apply-chaos (:network-out-bytes a []) drop-rate dup-rate reorder? (.nextLong rng))
                b-bytes (apply-chaos (:network-out-bytes b []) drop-rate dup-rate reorder? (.nextLong rng))

                ;; Process B receiving A's bytes
                b-rx (reduce (fn [res item]
                               (let [bb (if (map? item) (:packet item) item)
                                     bytes (bb->bytes bb)
                                     out (dc/handle-receive (:new-state res) bytes now-ms (java.net.InetSocketAddress. "127.0.0.1" 5000))]
                                 (-> out
                                     (dc/serialize-network-out)
                                     (update :app-events into (:app-events res []))
                                     (update :network-out-bytes into (:network-out-bytes res [])))))
                             (assoc b :network-out-bytes [] :app-events b-events)
                             a-bytes)

                ;; Process A receiving B's bytes
                a-rx (reduce (fn [res item]
                               (let [bb (if (map? item) (:packet item) item)
                                     bytes (bb->bytes bb)
                                     out (dc/handle-receive (:new-state res) bytes now-ms (java.net.InetSocketAddress. "127.0.0.1" 5001))]
                                 (-> out
                                     (dc/serialize-network-out)
                                     (update :app-events into (:app-events res []))
                                     (update :network-out-bytes into (:network-out-bytes res [])))))
                             (assoc a :network-out-bytes [] :app-events a-events)
                             b-bytes)

                ;; Process A timeouts
                a-time (reduce (fn [res [tid timer]]
                                 (if (>= now-ms (:expires-at timer))
                                   (let [out (dc/handle-timeout (:new-state res) tid now-ms (:dtls/engine (:new-state res)))]
                                     (-> out
                                         (dc/serialize-network-out)
                                         (update :app-events into (:app-events res []))
                                         (update :network-out-bytes into (:network-out-bytes res []))))
                                   res))
                               a-rx
                               (:timers (:new-state a-rx)))

                ;; Process B timeouts
                b-time (reduce (fn [res [tid timer]]
                                 (if (>= now-ms (:expires-at timer))
                                   (let [out (dc/handle-timeout (:new-state res) tid now-ms (:dtls/engine (:new-state res)))]
                                     (-> out
                                         (dc/serialize-network-out)
                                         (update :app-events into (:app-events res []))
                                         (update :network-out-bytes into (:network-out-bytes res []))))
                                   res))
                               b-rx
                               (:timers (:new-state b-rx)))]
            (recur a-time b-time (inc i))))))))

(def prop-chaos-connection
  (prop/for-all [drop-rate (gen/choose 0 20)
                 dup-rate (gen/choose 0 10)
                 reorder? gen/boolean
                 rand-seed gen/large-integer]
                (let [drop-rate (/ drop-rate 100.0)
                      dup-rate (/ dup-rate 100.0)
                      client-a (dc/create-connection {:ice-ufrag "Alice" :ice-pwd "pwdA" :remote-ice-ufrag "Bob" :remote-ice-pwd "pwdB" :ice-lite? false} true)
                      client-b (dc/create-connection {:ice-ufrag "Bob" :ice-pwd "pwdB" :remote-ice-ufrag "Alice" :remote-ice-pwd "pwdA" :ice-lite? false} false)

                      ;; Extract dynamically generated fingerprints
                      client-a (assoc client-a :remote-fingerprint (:fingerprint (:cert-data client-b)))
                      client-b (assoc client-b :remote-fingerprint (:fingerprint (:cert-data client-a)))

                      now-ms 0

                      client-a (assoc client-a :remote-candidates ["127.0.0.1:5001"])
                      client-b (assoc client-b :remote-candidates ["127.0.0.1:5000"])

                      client-a (assoc-in client-a [:timers :stun/keepalive :expires-at] 0)
                      client-b (assoc-in client-b [:timers :stun/keepalive :expires-at] 0)

                      init-res-b (-> (dc/handle-timeout client-b :stun/keepalive now-ms nil)
                                     (dc/serialize-network-out))

                      init-res-a (-> (dc/handle-event client-a {:type :connect} now-ms)
                                     (dc/serialize-network-out))

                      [conn-a conn-b _] (pump-chaos-network init-res-a init-res-b
                                                            (fn [a b] (not (and (= :connected (:ice-connection-state (:new-state a)))
                                                                                (= :connected (:ice-connection-state (:new-state b)))
                                                                                (= :established (:state (:new-state a)))
                                                                                (= :established (:state (:new-state b))))))
                                                            2000
                                                            now-ms
                                                            drop-rate dup-rate reorder? rand-seed)]

                  (if (not (and (= :connected (:ice-connection-state (:new-state conn-a)))
                                (= :connected (:ice-connection-state (:new-state conn-b)))
                                (= :established (:state (:new-state conn-a)))
                                (= :established (:state (:new-state conn-b)))))
                    false
                    (let [msg-bytes (.getBytes "Chaos Message Payload" "UTF-8")
                          send-res-a (-> (dc/send-data (:new-state conn-a) msg-bytes 0 :webrtc/string 0)
                                         (dc/serialize-network-out))

                          a-with-send (-> conn-a
                                          (assoc :new-state (:new-state send-res-a))
                                          (update :network-out-bytes into (:network-out-bytes send-res-a [])))

                          [_final-a final-b _] (pump-chaos-network a-with-send conn-b
                                                                   (fn [_a b] (not-any? #(= :on-message (:type %)) (:app-events b)))
                                                                   500
                                                                   0
                                                                   drop-rate dup-rate reorder? rand-seed)]
                      (boolean (some #(= :on-message (:type %)) (:app-events final-b))))))))

(defspec test-chaos-connection-establishment (Integer/parseInt (or (System/getenv "FUZZ_ITERS") "20")) prop-chaos-connection)
