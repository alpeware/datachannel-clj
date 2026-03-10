(ns datachannel.sans-io-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(defn pump-network
  "A deterministic, synchronous network loop for testing two Sans-IO endpoints."
  [state-a-atom state-b-atom initial-packets]
  (loop [packets-in-flight initial-packets
         events-a []
         events-b []
         iterations 0]
    ;; Stop if the network is quiet or we hit an infinite loop safety limit
    (if (or (empty? packets-in-flight) (> iterations 100))
      {:events-a events-a :events-b events-b}
      (let [{:keys [dest packet]} (first packets-in-flight)
            now-ms (System/currentTimeMillis)

            ;; Route to A
            res-a (when (= dest :a)
                    (core/handle-sctp-packet @state-a-atom packet now-ms))
            ;; Route to B
            res-b (when (= dest :b)
                    (core/handle-sctp-packet @state-b-atom packet now-ms))]

        ;; Advance states
        (when res-a (reset! state-a-atom (:new-state res-a)))
        (when res-b (reset! state-b-atom (:new-state res-b)))

        ;; Queue up the replies
        (let [new-pkts-from-a (if res-a (map (fn [p] {:dest :b :packet p}) (:network-out res-a)) [])
              new-pkts-from-b (if res-b (map (fn [p] {:dest :a :packet p}) (:network-out res-b)) [])
              next-packets (concat (rest packets-in-flight) new-pkts-from-a new-pkts-from-b)
              next-events-a (if res-a (into events-a (:app-events res-a)) events-a)
              next-events-b (if res-b (into events-b (:app-events res-b)) events-b)]

          (recur next-packets next-events-a next-events-b (inc iterations)))))))

(deftest sans-io-handshake-test
  (testing "Establish Connection using Sans-IO pump-network"
    (let [alice-result (core/create-connection {} true)
          bob-result (core/create-connection {} false)

          state-a-atom (atom alice-result)
          state-b-atom (atom bob-result)

          ;; Generate initial connect event
          now-ms (System/currentTimeMillis)
          init-res (core/handle-event @state-a-atom {:type :connect} now-ms)]

      ;; Update Alice state with the generated INIT
      (reset! state-a-atom (:new-state init-res))

      ;; Initial packets generated from connect event
      (let [init-packet (first (:network-out init-res))
            ;; Fix initial-tsn missing in packet decoding simulation
            init-packet (update-in init-packet [:chunks 0] assoc :initial-tsn 0 :inbound-streams 10 :outbound-streams 10)
            initial-packets [{:dest :b :packet init-packet}]

            ;; Run the network
            results (pump-network state-a-atom state-b-atom initial-packets)

            events-a (:events-a results)
            events-b (:events-b results)]

        (is (= :established (:state @state-a-atom)) "Alice should be established")
        (is (= :established (:state @state-b-atom)) "Bob should be established")

        (is (some #(= :on-open (:type %)) events-a) "Alice should have received :on-open event")
        (is (some #(= :on-open (:type %)) events-b) "Bob should have received :on-open event")))))
