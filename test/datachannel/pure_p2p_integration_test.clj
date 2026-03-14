(ns datachannel.pure-p2p-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as dc])
  (:import [java.nio ByteBuffer]))

(defn- bb->bytes [^ByteBuffer bb]
  (let [buf (byte-array (.remaining bb))]
    (.get (.duplicate bb) buf)
    buf))

(defn pump-network "TODO"
  [state-a state-b condition? max-iterations start-time]
  (loop [a state-a
         b state-b
         i 0]
    (let [now-ms (+ start-time (* i 100))
          a-events (:app-events a [])
          b-events (:app-events b [])]
      (if (or (>= i max-iterations)
              (not (condition? a b)))
        [a b]
        (let [a-bytes (:network-out-bytes a [])
              b-bytes (:network-out-bytes b [])

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
          (Thread/sleep 10)
          (recur a-time b-time (inc i)))))))

(deftest pure-p2p-integration-test
  (testing "End-to-End P2P using handle-receive and serialize-network-out boundaries"
    (let [client-a (dc/create-connection {:ice-ufrag "Alice" :ice-pwd "pwdA" :remote-ice-ufrag "Bob" :remote-ice-pwd "pwdB" :ice-lite? false} true)
          client-b (dc/create-connection {:ice-ufrag "Bob" :ice-pwd "pwdB" :remote-ice-ufrag "Alice" :remote-ice-pwd "pwdA" :ice-lite? false} false)

          ;; Extract dynamically generated fingerprints
          client-a (assoc client-a :remote-fingerprint (:fingerprint (:cert-data client-b)))
          client-b (assoc client-b :remote-fingerprint (:fingerprint (:cert-data client-a)))

          ;; Start connection by triggering a :connect event on A
          now-ms (System/currentTimeMillis)

          ;; Inject local/remote ICE candidates to allow Full ICE negotiation
          client-a (assoc client-a :remote-candidates ["127.0.0.1:5001"])
          client-b (assoc client-b :remote-candidates ["127.0.0.1:5000"])

          ;; Prime timers to fire immediately
          client-a (assoc-in client-a [:timers :stun/keepalive :expires-at] 0)
          client-b (assoc-in client-b [:timers :stun/keepalive :expires-at] 0)

          ;; For B to start sending STUNs immediately too, we can trigger a generic timeout
          init-res-b (-> (dc/handle-timeout client-b :stun/keepalive now-ms nil)
                         (dc/serialize-network-out))

          init-res-a (-> (dc/handle-event client-a {:type :connect} now-ms)
                         (dc/serialize-network-out))

          ;; Pump until connection is open (or max iterations)
          [conn-a conn-b] (pump-network init-res-a init-res-b
                                        (fn [a b] (not (and (= :connected (:ice-connection-state (:new-state a)))
                                                            (= :connected (:ice-connection-state (:new-state b)))
                                                            (= :established (:state (:new-state a)))
                                                            (= :established (:state (:new-state b))))))
                                        1000
                                        now-ms)]

      ;; Verify ICE reached connected state before SCTP
      (is (= :connected (:ice-connection-state (:new-state conn-a))) "Peer A ICE should be connected")
      (is (= :connected (:ice-connection-state (:new-state conn-b))) "Peer B ICE should be connected")

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
            [_final-a final-b] (pump-network a-with-send conn-b
                                             (fn [_a b] (not-any? #(= :on-message (:type %)) (:app-events b)))
                                             50
                                             (System/currentTimeMillis))]
        (is (some #(= :on-message (:type %)) (:app-events final-b)) "Peer B should have received the message")
        (let [msg-event (first (filter #(= :on-message (:type %)) (:app-events final-b)))]
          (is (= "Hello P2P!" (String. (:payload msg-event) "UTF-8")) "Message content should match"))))))
