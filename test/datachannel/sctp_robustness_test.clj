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

(deftest attempt-connect-without-cookie-test
  (testing "Attempt Connect Without Cookie"
    (let [client-state (atom {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0})
          client-out (java.util.concurrent.LinkedBlockingQueue.)
          client-opened (atom false)
          client-conn {:state client-state
                       :sctp-out client-out
                       :on-open (atom (fn [] (reset! client-opened true)))}

          server-state (atom {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0})
          server-out (java.util.concurrent.LinkedBlockingQueue.)
          server-conn {:state server-state
                       :sctp-out server-out
                       :on-open (atom (fn [] nil))}

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

        ;; Mutate INIT-ACK to remove the state cookie parameter
        (let [malformed-init-ack-packet (update-in init-ack-packet [:chunks 0 :params] dissoc :cookie)]

          ;; Deliver malformed INIT-ACK to client
          (handle-sctp-packet malformed-init-ack-packet client-conn)

          ;; Client should NOT produce COOKIE-ECHO because cookie is missing
          (let [cookie-echo-packet (.poll client-out)]
            (is (nil? cookie-echo-packet) "Client should drop INIT-ACK without cookie and not produce COOKIE-ECHO")))))))

(deftest send-message-after-established-test
  (testing "Send Message After Established"
    (let [client-state (atom {:remote-ver-tag 2222 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :remote-tsn 200})
          client-out (java.util.concurrent.LinkedBlockingQueue.)
          client-conn {:state client-state
                       :sctp-out client-out
                       :on-message (atom nil)
                       :on-data (atom nil)}

          server-state (atom {:remote-ver-tag 1111 :local-ver-tag 2222 :next-tsn 201 :ssn 0 :remote-tsn 99})
          server-out (java.util.concurrent.LinkedBlockingQueue.)
          server-received (atom nil)
          server-conn {:state server-state
                       :sctp-out server-out
                       :on-message (atom (fn [payload] (reset! server-received (String. ^bytes payload))))
                       :on-data (atom nil)}

          handle-sctp-packet #'core/handle-sctp-packet]

      ;; Client sends DATA
      (core/send-data client-conn (.getBytes "Hello") 0 :webrtc/string)
      (let [data-packet (.poll client-out)]
        (is data-packet "Client should produce DATA packet")
        (is (= :data (-> data-packet :chunks first :type)))

        ;; Deliver to server
        (handle-sctp-packet (assoc data-packet :src-port 5000 :dst-port 5001) server-conn)

        (is (= "Hello" @server-received) "Server should receive the message")

        ;; Server should produce SACK
        (let [sack-packet (.poll server-out)]
          (is sack-packet "Server should produce SACK packet")
          (is (= :sack (-> sack-packet :chunks first :type)))
          (is (= 100 (-> sack-packet :chunks first :cum-tsn-ack)) "SACK should ack TSN 100"))))))

(deftest send-many-messages-test
  (testing "Send Many Messages"
    (let [client-state (atom {:remote-ver-tag 2222 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :remote-tsn 200})
          client-out (java.util.concurrent.LinkedBlockingQueue.)
          client-conn {:state client-state
                       :sctp-out client-out
                       :on-message (atom nil)
                       :on-data (atom nil)}

          server-state (atom {:remote-ver-tag 1111 :local-ver-tag 2222 :next-tsn 201 :ssn 0 :remote-tsn 99})
          server-out (java.util.concurrent.LinkedBlockingQueue.)
          server-received (atom 0)
          server-conn {:state server-state
                       :sctp-out server-out
                       :on-message (atom (fn [_] (swap! server-received inc)))
                       :on-data (atom nil)}

          handle-sctp-packet #'core/handle-sctp-packet
          iterations 100]

      (dotimes [i iterations]
        (core/send-data client-conn (.getBytes (str "Message " i)) 0 :webrtc/string))

      (dotimes [i iterations]
        (let [data-packet (.poll client-out)]
          (is data-packet (str "Client should produce DATA packet " i))
          (is (= :data (-> data-packet :chunks first :type)))

          ;; Deliver to server
          (handle-sctp-packet (assoc data-packet :src-port 5000 :dst-port 5001) server-conn)

          ;; Server should produce SACK
          (let [sack-packet (.poll server-out)]
            (is sack-packet "Server should produce SACK packet")
            (is (= :sack (-> sack-packet :chunks first :type))))))

      (is (= iterations @server-received) "Server should receive all messages"))))

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

(deftest establish-simultaneous-connection-test
  (testing "Establish Simultaneous Connection"
    (let [client-a-state (atom {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0})
          client-a-out (java.util.concurrent.LinkedBlockingQueue.)
          client-a-opened (atom false)
          client-a-conn {:state client-a-state
                         :sctp-out client-a-out
                         :on-open (atom (fn [] (reset! client-a-opened true)))}

          client-z-state (atom {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0})
          client-z-out (java.util.concurrent.LinkedBlockingQueue.)
          client-z-opened (atom false)
          client-z-conn {:state client-z-state
                         :sctp-out client-z-out
                         :on-open (atom (fn [] (reset! client-z-opened true)))}

          handle-sctp-packet #'core/handle-sctp-packet

          ;; A initiates connection
          init-packet-a {:src-port 5000 :dst-port 5001 :verification-tag 0
                         :chunks [{:type :init
                                   :init-tag (:local-ver-tag @client-a-state)
                                   :a-rwnd 100000
                                   :outbound-streams 10
                                   :inbound-streams 10
                                   :initial-tsn (:next-tsn @client-a-state)
                                   :params {}}]}
          ;; Z also initiates connection
          init-packet-z {:src-port 5001 :dst-port 5000 :verification-tag 0
                         :chunks [{:type :init
                                   :init-tag (:local-ver-tag @client-z-state)
                                   :a-rwnd 100000
                                   :outbound-streams 10
                                   :inbound-streams 10
                                   :initial-tsn (:next-tsn @client-z-state)
                                   :params {}}]}]

      ;; In simultaneous connect, A sends INIT to Z, Z sends INIT to A.
      ;; We simulate A's INIT reaching Z, and Z's INIT reaching A simultaneously.

      ;; A receives Z's INIT
      (handle-sctp-packet init-packet-z client-a-conn)
      ;; Z receives A's INIT
      (handle-sctp-packet init-packet-a client-z-conn)

      ;; A should produce INIT-ACK for Z
      (let [init-ack-from-a (.poll client-a-out)]
        (is init-ack-from-a "A should produce INIT-ACK")
        (is (= :init-ack (-> init-ack-from-a :chunks first :type)))
        ;; Deliver to Z
        (handle-sctp-packet init-ack-from-a client-z-conn))

      ;; Z should produce INIT-ACK for A
      (let [init-ack-from-z (.poll client-z-out)]
        (is init-ack-from-z "Z should produce INIT-ACK")
        (is (= :init-ack (-> init-ack-from-z :chunks first :type)))
        ;; Deliver to A
        (handle-sctp-packet init-ack-from-z client-a-conn))

      ;; A receives Z's INIT-ACK, generates COOKIE-ECHO for Z
      (let [cookie-echo-from-a (.poll client-a-out)]
        (is cookie-echo-from-a "A should produce COOKIE-ECHO")
        (is (= :cookie-echo (-> cookie-echo-from-a :chunks first :type)))
        ;; Deliver to Z
        (handle-sctp-packet cookie-echo-from-a client-z-conn))

      ;; Z receives A's INIT-ACK, generates COOKIE-ECHO for A
      (let [cookie-echo-from-z (.poll client-z-out)]
        (is cookie-echo-from-z "Z should produce COOKIE-ECHO")
        (is (= :cookie-echo (-> cookie-echo-from-z :chunks first :type)))
        ;; Deliver to A
        (handle-sctp-packet cookie-echo-from-z client-a-conn))

      ;; A processes Z's COOKIE-ECHO, generates COOKIE-ACK, becomes established
      (let [cookie-ack-from-a (.poll client-a-out)]
        (is cookie-ack-from-a "A should produce COOKIE-ACK")
        (is (= :cookie-ack (-> cookie-ack-from-a :chunks first :type)))
        (is (true? @client-a-opened) "A should be in open state")
        ;; Deliver to Z
        (handle-sctp-packet cookie-ack-from-a client-z-conn))

      ;; Z processes A's COOKIE-ECHO, generates COOKIE-ACK, becomes established
      (let [cookie-ack-from-z (.poll client-z-out)]
        (is cookie-ack-from-z "Z should produce COOKIE-ACK")
        (is (= :cookie-ack (-> cookie-ack-from-z :chunks first :type)))
        (is (true? @client-z-opened) "Z should be in open state")
        ;; Deliver to A
        (handle-sctp-packet cookie-ack-from-z client-a-conn))

      ;; Verify state tags
      (is (= 2222 (:remote-ver-tag @client-a-state)))
      (is (= 1111 (:remote-ver-tag @client-z-state))))))

(deftest establish-connection-lost-cookie-ack-test
  (testing "Establish Connection Lost Cookie Ack"
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
        (handle-sctp-packet init-packet server-conn))

      ;; 2. Server processes INIT and generates INIT-ACK
      (let [init-ack-packet (.poll server-out)]
        (is init-ack-packet "Server should produce INIT-ACK")
        (is (= :init-ack (-> init-ack-packet :chunks first :type)))
        (handle-sctp-packet init-ack-packet client-conn))

      ;; 3. Client processes INIT-ACK and generates COOKIE-ECHO
      (let [cookie-echo-packet (.poll client-out)]
        (is cookie-echo-packet "Client should produce COOKIE-ECHO")
        (is (= :cookie-echo (-> cookie-echo-packet :chunks first :type)))
        (handle-sctp-packet cookie-echo-packet server-conn)

        ;; 4. Server processes COOKIE-ECHO, generates COOKIE-ACK, and becomes established
        (let [cookie-ack-packet (.poll server-out)]
          (is cookie-ack-packet "Server should produce COOKIE-ACK")
          (is (= :cookie-ack (-> cookie-ack-packet :chunks first :type)))
          (is (true? @server-opened) "Server should be in open state")

          ;; Simulate COOKIE-ACK loss (do not deliver to client)
          (is (false? @client-opened) "Client should NOT be in open state yet")

          ;; Client doesn't know it's established because COOKIE-ACK was lost.
          ;; Note: We simulate the client's timeout loop by manually feeding the
          ;; originally intercepted COOKIE-ECHO packet back to the server.
          ;; Simulate client timeout/retransmission of COOKIE-ECHO
          (handle-sctp-packet cookie-echo-packet server-conn)

          ;; Server should process the duplicate COOKIE-ECHO and generate another COOKIE-ACK
          (let [cookie-ack-packet2 (.poll server-out)]
            (is cookie-ack-packet2 "Server should produce another COOKIE-ACK")
            (is (= :cookie-ack (-> cookie-ack-packet2 :chunks first :type)))

            ;; Deliver the second COOKIE-ACK to client
            (handle-sctp-packet cookie-ack-packet2 client-conn)

            ;; Client processes COOKIE-ACK and becomes established
            (is (true? @client-opened) "Client should be in open state")))))))

(deftest resend-init-and-establish-connection-test
  (testing "Resend Init And Establish Connection"
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
        ;; Note: The client normally queues the INIT packet in its sctp-out queue.
        ;; Here we are feeding it manually as we mock the 4-way handshake loop.
        ;; Simulate INIT loss (do not deliver to server's handle-sctp-packet)
        (is (false? @server-opened) "Server should not be established")

        ;; Since INIT was dropped, the server never produced INIT-ACK.
        ;; Simulate client timeout/retransmission of INIT
        (handle-sctp-packet init-packet server-conn))

      ;; 2. Server processes retransmitted INIT and generates INIT-ACK
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
      (is (true? @client-opened) "Client should be in open state"))))

(deftest resend-cookie-echo-and-establish-connection-test
  (testing "Resend Cookie Echo And Establish Connection"
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

        ;; Simulate COOKIE-ECHO loss (do not deliver to server's handle-sctp-packet)
        (is (false? @server-opened) "Server should NOT be in open state yet")
        (is (false? @client-opened) "Client should NOT be in open state yet")

        ;; Client doesn't know it's established because COOKIE-ACK was never sent (COOKIE-ECHO lost).
        ;; Note: We simulate the timeout by manually feeding the intercepted COOKIE-ECHO packet
        ;; again to the server, as the mock connection lacks the timer loop that would re-queue it.
        ;; Simulate client timeout/retransmission of COOKIE-ECHO
        (handle-sctp-packet cookie-echo-packet server-conn))

      ;; 4. Server processes retransmitted COOKIE-ECHO, generates COOKIE-ACK, and becomes established
      (let [cookie-ack-packet (.poll server-out)]
        (is cookie-ack-packet "Server should produce COOKIE-ACK")
        (is (= :cookie-ack (-> cookie-ack-packet :chunks first :type)))
        (is (true? @server-opened) "Server should be in open state")

        ;; Deliver to client
        (handle-sctp-packet cookie-ack-packet client-conn))

      ;; Client processes COOKIE-ACK and becomes established
      (is (true? @client-opened) "Client should be in open state"))))
