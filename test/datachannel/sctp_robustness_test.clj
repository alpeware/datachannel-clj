(ns datachannel.sctp-robustness-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest tsn-wraparound-test
  (testing "TSN wraparound handling"
    (let [state (atom {:remote-tsn 4294967295 :remote-ver-tag 123})
          connection {:state state
                      :sctp-out (java.util.concurrent.LinkedBlockingQueue.)
                      :on-message (atom nil)
                      :on-data (atom nil)}
          ;; Accessing private handle-sctp-packet for testing
          handle-sctp-packet #'core/handle-sctp-packet
          packet {:src-port 5000 :dst-port 5000
                  :chunks [{:type :data :tsn 0 :protocol :webrtc/string :payload (byte-array 0)}]}]

      (handle-sctp-packet packet connection)
      (is (= 0 (:remote-tsn @state)) "TSN 0 should be considered newer than 4294967295")

      (handle-sctp-packet {:src-port 5000 :dst-port 5000
                           :chunks [{:type :data :tsn 10 :protocol :webrtc/string :payload (byte-array 0)}]}
                          connection)
      (is (= 10 (:remote-tsn @state)) "TSN 10 should be considered newer than 0")

      (handle-sctp-packet {:src-port 5000 :dst-port 5000
                           :chunks [{:type :data :tsn 5 :protocol :webrtc/string :payload (byte-array 0)}]}
                          connection)
      (is (= 10 (:remote-tsn @state)) "Old TSN 5 should NOT update remote-tsn (still 10)"))))

(deftest unordered-delivery-test
  (testing "Unordered delivery Head-of-Line blocking"
    (let [received (atom [])
          state (atom {:remote-tsn 0 :remote-ver-tag 123})
          connection {:state state
                      :sctp-out (java.util.concurrent.LinkedBlockingQueue.)
                      :on-message (atom (fn [payload] (swap! received conj (String. ^bytes payload))))
                      :on-data (atom nil)}
          handle-sctp-packet #'core/handle-sctp-packet]

      ;; In unordered delivery, if we receive TSN 2 before TSN 1, it should still be delivered immediately.
      ;; Note: Current implementation in core.clj delivers EVERY data chunk immediately
      ;; as long as it's not a duplicate (actually it delivers everything right now,
      ;; it just updates remote-tsn if it's newer).

      (handle-sctp-packet {:src-port 5000 :dst-port 5000
                           :chunks [{:type :data :tsn 2 :protocol :webrtc/string
                                     :payload (.getBytes "Second") :unordered true}]}
                          connection)

      (is (= ["Second"] @received) "TSN 2 should be delivered even if TSN 1 is missing (unordered)")

      (handle-sctp-packet {:src-port 5000 :dst-port 5000
                           :chunks [{:type :data :tsn 1 :protocol :webrtc/string
                                     :payload (.getBytes "First") :unordered true}]}
                          connection)

      (is (= ["Second" "First"] @received) "TSN 1 should be delivered when it arrives"))))

(deftest establish-connection-test
  (testing "Full 4-way handshake (Establish Connection)"
    (let [client-state (atom {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0})
          client-out (java.util.concurrent.LinkedBlockingQueue.)
          client-opened (atom false)
          client-conn {:state client-state
                       :sctp-out client-out
                       :on-open (atom (fn [] (reset! client-opened true)))}

          server-state (atom {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0})
          server-out (java.util.concurrent.LinkedBlockingQueue.)
          server-opened (atom false)
          server-conn {:state server-state
                       :sctp-out server-out
                       :on-open (atom (fn [] (reset! server-opened true)))}

          handle-sctp-packet #'core/handle-sctp-packet]

      ;; 1. Client initiates connection with INIT
      (let [init-packet {:src-port 5000 :dst-port 5000 :verification-tag 0
                         :chunks [{:type :init
                                   :init-tag (:local-ver-tag @client-state)
                                   :a-rwnd 100000
                                   :outbound-streams 10
                                   :inbound-streams 10
                                   :initial-tsn (:next-tsn @client-state)
                                   :params {}}]}]
        ;; Direct to server
        (handle-sctp-packet init-packet server-conn))

      ;; 2. Server processes INIT and generates INIT-ACK
      (let [init-ack-packet (.poll server-out)]
        (is init-ack-packet "Server should produce INIT-ACK")
        (is (= :init-ack (-> init-ack-packet :chunks first :type)))
        ;; Deliver to client
        (handle-sctp-packet init-ack-packet client-conn))

      ;; 3. Client processes INIT-ACK and generates COOKIE-ECHO
      (let [cookie-echo-packet (.poll client-out)]
        (is cookie-echo-packet "Client should produce COOKIE-ECHO")
        (is (= :cookie-echo (-> cookie-echo-packet :chunks first :type)))
        ;; Deliver to server
        (handle-sctp-packet cookie-echo-packet server-conn))

      ;; 4. Server processes COOKIE-ECHO, generates COOKIE-ACK, and becomes established
      (let [cookie-ack-packet (.poll server-out)]
        (is cookie-ack-packet "Server should produce COOKIE-ACK")
        (is (= :cookie-ack (-> cookie-ack-packet :chunks first :type)))
        (is (true? @server-opened) "Server should be in open state")
        ;; Deliver to client
        (handle-sctp-packet cookie-ack-packet client-conn))

      ;; Client processes COOKIE-ACK and becomes established
      (is (true? @client-opened) "Client should be in open state")

      ;; Verify state tags
      (is (= 2222 (:remote-ver-tag @client-state)))
      (is (= 1111 (:remote-ver-tag @server-state))))))

(deftest sending-heartbeat-answers-with-ack-test
  (testing "Sending Heartbeat Answers With Ack"
    (let [state (atom {:remote-ver-tag 12345})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          connection {:state state
                      :sctp-out out-queue}
          handle-sctp-packet #'core/handle-sctp-packet
          heartbeat-params {:heartbeat-info (byte-array [1 2 3 4])}
          packet {:src-port 5000 :dst-port 5001 :verification-tag 12345
                  :chunks [{:type :heartbeat :params heartbeat-params}]}]

      (handle-sctp-packet packet connection)

      (let [response-packet (.poll out-queue)]
        (is response-packet "Should produce a response packet")
        (is (= 5001 (:src-port response-packet)) "Should swap src/dst ports")
        (is (= 5000 (:dst-port response-packet)) "Should swap src/dst ports")
        (is (= 12345 (:verification-tag response-packet)) "Should use same verification tag")

        (let [chunk (first (:chunks response-packet))]
          (is (= :heartbeat-ack (:type chunk)) "Chunk should be HEARTBEAT-ACK")
          (is (= (seq (:heartbeat-info heartbeat-params))
                 (seq (:heartbeat-info (:params chunk))))
              "HEARTBEAT-ACK should echo the exact params from the HEARTBEAT chunk"))))))
