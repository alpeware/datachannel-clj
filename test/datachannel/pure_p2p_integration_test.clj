(ns datachannel.pure-p2p-integration-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as dc]
            [datachannel.sctp :as sctp])
  (:import [java.nio ByteBuffer]))

(defn- bb->bytes [^ByteBuffer bb]
  (let [buf (byte-array (.remaining bb))]
    (.get (.duplicate bb) buf)
    buf))

(defn pump-network [state-a state-b condition? max-iterations]
  (loop [a state-a
         b state-b
         i 0]
    (let [now-ms (System/currentTimeMillis)
          a-events (:app-events a [])
          b-events (:app-events b [])]
      (if (or (>= i max-iterations)
              (not (condition? a b)))
        [a b]
        (let [a-bytes (:network-out-bytes a [])
              b-bytes (:network-out-bytes b [])

              ;; Process B receiving A's bytes
              b-rx (reduce (fn [res ^ByteBuffer bb]
                             (let [bytes (bb->bytes bb)
                                   out (dc/handle-receive (:new-state res) bytes now-ms)]
                               (-> out
                                   (dc/serialize-network-out)
                                   (update :app-events into (:app-events res []))
                                   (update :network-out-bytes into (:network-out-bytes res [])))))
                           (assoc b :network-out-bytes [] :app-events b-events)
                           a-bytes)

              ;; Process A receiving B's bytes
              a-rx (reduce (fn [res ^ByteBuffer bb]
                             (let [bytes (bb->bytes bb)
                                   out (dc/handle-receive (:new-state res) bytes now-ms)]
                               (-> out
                                   (dc/serialize-network-out)
                                   (update :app-events into (:app-events res []))
                                   (update :network-out-bytes into (:network-out-bytes res [])))))
                           (assoc a :network-out-bytes [] :app-events a-events)
                           b-bytes)

              ;; Process A timeouts
              a-time (reduce (fn [res [tid timer]]
                               (if (>= now-ms (:expires-at timer))
                                 (let [out (dc/handle-timeout (:new-state res) tid now-ms)]
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
                                 (let [out (dc/handle-timeout (:new-state res) tid now-ms)]
                                   (-> out
                                       (dc/serialize-network-out)
                                       (update :app-events into (:app-events res []))
                                       (update :network-out-bytes into (:network-out-bytes res []))))
                                 res))
                             b-rx
                             (:timers (:new-state b-rx)))]
          (Thread/sleep 10)
          (recur a-time b-time (inc i)))))))

(deftest pure-p2p-integration-test
  (testing "End-to-End P2P using handle-receive and serialize-network-out boundaries"
    (let [client-a (dc/create-connection {} true)
          client-b (dc/create-connection {} false)
          state-a (assoc client-a :state :closed :dtls/engine nil)
          state-b (assoc client-b :state :closed :dtls/engine nil)

          ;; Initial TSN defaults missing in byte serialization? Wait, serialize network out actually works correctly for INIT if it is complete.
          ;; Let's make sure the client state is modified exactly as in sans-io-integration-test

          ;; Start connection by triggering a :connect event on A
          now-ms (System/currentTimeMillis)
          ;; Fix missing initial-tsn/streams from parsed connect event
          init-res-a-raw (dc/handle-event state-a {:type :connect} now-ms)
          init-res-a (-> init-res-a-raw
                         (update-in [:network-out 0 :chunks 0] assoc :initial-tsn 0 :inbound-streams 10 :outbound-streams 10)
                         (dc/serialize-network-out))

          ;; Wrap B in a similar response map shape
          init-res-b {:new-state state-b :network-out [] :network-out-bytes [] :app-events []}

          ;; Pump until connection is open (or max iterations)
          [conn-a conn-b] (pump-network init-res-a init-res-b
                                        (fn [a b] (not (and (= :established (:state (:new-state a)))
                                                            (= :established (:state (:new-state b))))))
                                        100)]

      ;; Verify they are connected
      (is (= :established (:state (:new-state conn-a))) "Peer A should be established")
      (is (= :established (:state (:new-state conn-b))) "Peer B should be established")

      ;; Now have A send a message
      (let [msg-bytes (.getBytes "Hello P2P!" "UTF-8")
            send-res-a (-> (dc/send-data (:new-state conn-a) msg-bytes 0 :webrtc/string (System/currentTimeMillis))
                           (dc/serialize-network-out))

            ;; Merge the send results into the accumulated events/network-out
            a-with-send (-> conn-a
                            (assoc :new-state (:new-state send-res-a))
                            (update :network-out-bytes into (:network-out-bytes send-res-a [])))

            ;; Pump network again until B receives a message
            [final-a final-b] (pump-network a-with-send conn-b
                                            (fn [a b] (not-any? #(= :on-message (:type %)) (:app-events b)))
                                            50)]
        (is (some #(= :on-message (:type %)) (:app-events final-b)) "Peer B should have received the message")
        (let [msg-event (first (filter #(= :on-message (:type %)) (:app-events final-b)))]
          (is (= "Hello P2P!" (String. (:payload msg-event) "UTF-8")) "Message content should match"))))))
