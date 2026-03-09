(ns datachannel.pure-p2p-integration-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as dc]
            [datachannel.sctp :as sctp])
  (:import [java.nio ByteBuffer]))

(defn- bb->bytes [^ByteBuffer bb]
  (let [buf (byte-array (.remaining bb))]
    (.get (.duplicate bb) buf)
    buf))

(defn pump-network [state-a state-b max-iterations]
  (loop [a state-a
         b state-b
         i 0]
    (let [now-ms (System/currentTimeMillis)
          a-events (:app-events a [])
          b-events (:app-events b [])]
      (if (or (>= i max-iterations)
              (some #(= :on-message (:type %)) a-events)
              (some #(= :on-message (:type %)) b-events))
        {:a a :b b :iterations i}
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
          state-a (assoc @(:state (:connection client-a)) :state :closed)
          state-b (assoc @(:state (:connection client-b)) :state :closed)

          ;; Initial TSN defaults missing in byte serialization? Wait, serialize network out actually works correctly for INIT if it is complete.
          ;; Let's make sure the client state is modified exactly as in sans-io-integration-test

          ;; Start connection by triggering a :connect event on A
          now-ms (System/currentTimeMillis)
          init-res-a (-> (dc/handle-event state-a {:type :connect} now-ms)
                         (dc/serialize-network-out))

          ;; Wrap B in a similar response map shape
          init-res-b {:new-state state-b :network-out [] :network-out-bytes [] :app-events []}

          ;; Pump until connection is open (or max iterations)
          connected-res (pump-network init-res-a init-res-b 50)

          conn-a (:a connected-res)
          conn-b (:b connected-res)]

      ;; Verify they are connected
      (is (= :established (:state (:new-state conn-a))) "Peer A should be established")
      (is (= :established (:state (:new-state conn-b))) "Peer B should be established")

      ;; Now have A send a message
      (let [msg-bytes (.getBytes "Hello P2P!" "UTF-8")
            send-res-a (-> (dc/send-data (:new-state conn-a) msg-bytes 0 51 (System/currentTimeMillis))
                           (dc/serialize-network-out))

            ;; Merge the send results into the accumulated events/network-out
            a-with-send (-> conn-a
                            (assoc :new-state (:new-state send-res-a))
                            (update :network-out-bytes into (:network-out-bytes send-res-a [])))

            ;; Reset app events for the next phase
            a-clean (assoc a-with-send :app-events [])
            b-clean (assoc conn-b :app-events [])

            final-res (pump-network a-clean b-clean 50)

            final-b (:b final-res)
            on-message-events (filter #(= :on-message (:type %)) (:app-events final-b))]

        (is (not-empty on-message-events) "Peer B should have received an :on-message event")
        (when (not-empty on-message-events)
          (is (= "Hello P2P!" (String. (:payload (first on-message-events)) "UTF-8")) "Payload should match"))))))
