(ns datachannel.sctp-init-ack-robustness-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest connection-can-continue-from-first-init-ack-test
  (testing "Connection Can Continue From First Init Ack"
    (let [client-state (atom {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :state :closed})
          client-out (java.util.concurrent.LinkedBlockingQueue.)
          client-opened (atom false)
          client-conn {:state client-state
                       :sctp-out client-out
                       :on-open (atom (fn [] (reset! client-opened true)))}

          server-state (atom {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0 :state :closed})
          server-out (java.util.concurrent.LinkedBlockingQueue.)
          server-opened (atom false)
          server-conn {:state server-state
                       :sctp-out server-out
                       :on-open (atom (fn [] (reset! server-opened true)))}

          handle-sctp-packet (fn [c p]
                               (when (and p c)
                                 (let [state-map @(:state c)
                                       res (core/handle-sctp-packet state-map p (System/currentTimeMillis))
                                       next-state (:new-state res)
                                       network-out (:network-out res)
                                       app-events (:app-events res)]
                                   (reset! (:state c) next-state)
                                   (doseq [out network-out] (.offer (:sctp-out c) out))
                                   (doseq [evt app-events]
                                     (case (:type evt)
                                       :on-message (when-let [cb (:on-message c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f (:payload evt)))) (when cb (cb (:payload evt)))))
                                       :on-data (when-let [cb (:on-data c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f (assoc evt :payload (:payload evt) :stream-id (:stream-id evt))))) (when cb (cb (assoc evt :payload (:payload evt) :stream-id (:stream-id evt))))))
                                       :on-open (when-let [cb (:on-open c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f))) (when cb (cb))))
                                       :on-error (when-let [cb (:on-error c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f (:causes evt)))) (when cb (cb (:causes evt)))))
                                       :on-close (when-let [cb (:on-close c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f))) (when cb (cb))))
                                       nil)))))]

      ;; 1. Client initiates connection with INIT
      (reset! client-state (assoc @client-state :state :cookie-wait))
      (let [init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                         :chunks [{:type :init
                                   :init-tag (:local-ver-tag @client-state)
                                   :a-rwnd 100000
                                   :outbound-streams 10
                                   :inbound-streams 10
                                   :initial-tsn (:next-tsn @client-state)
                                   :params {}}]}]

        ;; Server receives INIT first time
        (handle-sctp-packet server-conn init-packet)

        ;; Server generates first INIT-ACK
        (let [init-ack-packet1 (.poll server-out)]
          (is init-ack-packet1 "Server should produce first INIT-ACK")

          ;; Server receives the exact same INIT again (e.g. retransmission by client)
          (handle-sctp-packet server-conn init-packet)

          ;; Server generates another INIT-ACK
          (let [init-ack-packet2 (.poll server-out)]
            (is init-ack-packet2 "Server should produce second INIT-ACK")

            ;; Verify that they are indeed distinct INIT-ACKs (cookie differs)
            (let [chunk1 (first (:chunks init-ack-packet1))
                  chunk2 (first (:chunks init-ack-packet2))]
              (is (not= (:cookie (:params chunk1))
                        (:cookie (:params chunk2)))
                  "Second INIT-ACK should have a different cookie than the first"))

            ;; Client proceeds using the FIRST INIT-ACK
            (handle-sctp-packet client-conn init-ack-packet1)

            (let [cookie-echo-packet (.poll client-out)]
              (is cookie-echo-packet "Client should produce COOKIE-ECHO in response to INIT-ACK1")

              ;; Server receives COOKIE-ECHO
              (handle-sctp-packet server-conn cookie-echo-packet)

              (let [cookie-ack-packet (.poll server-out)]
                (is cookie-ack-packet "Server should produce COOKIE-ACK in response to COOKIE-ECHO")

                ;; Verify server state transitioned properly
                (is (= :established (:state @server-state)) "Server should transition to established")
                (is (true? @server-opened) "Server on-open should be called")

                ;; Client receives COOKIE-ACK
                (handle-sctp-packet client-conn cookie-ack-packet)
                (is (= :established (:state @client-state)) "Client should transition to established")
                (is (true? @client-opened) "Client on-open should be called")))))))))

(deftest connection-can-continue-from-second-init-ack-test
  (testing "Connection Can Continue From Second Init Ack"
    (let [client-state (atom {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :state :closed})
          client-out (java.util.concurrent.LinkedBlockingQueue.)
          client-opened (atom false)
          client-conn {:state client-state
                       :sctp-out client-out
                       :on-open (atom (fn [] (reset! client-opened true)))}

          server-state (atom {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0 :state :closed})
          server-out (java.util.concurrent.LinkedBlockingQueue.)
          server-opened (atom false)
          server-conn {:state server-state
                       :sctp-out server-out
                       :on-open (atom (fn [] (reset! server-opened true)))}

          handle-sctp-packet (fn [c p]
                               (when (and p c)
                                 (let [state-map @(:state c)
                                       res (core/handle-sctp-packet state-map p (System/currentTimeMillis))
                                       next-state (:new-state res)
                                       network-out (:network-out res)
                                       app-events (:app-events res)]
                                   (reset! (:state c) next-state)
                                   (doseq [out network-out] (.offer (:sctp-out c) out))
                                   (doseq [evt app-events]
                                     (case (:type evt)
                                       :on-message (when-let [cb (:on-message c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f (:payload evt)))) (when cb (cb (:payload evt)))))
                                       :on-data (when-let [cb (:on-data c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f (assoc evt :payload (:payload evt) :stream-id (:stream-id evt))))) (when cb (cb (assoc evt :payload (:payload evt) :stream-id (:stream-id evt))))))
                                       :on-open (when-let [cb (:on-open c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f))) (when cb (cb))))
                                       :on-error (when-let [cb (:on-error c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f (:causes evt)))) (when cb (cb (:causes evt)))))
                                       :on-close (when-let [cb (:on-close c)] (if (instance? clojure.lang.IRef cb) (let [f @cb] (when f (f))) (when cb (cb))))
                                       nil)))))]

      ;; 1. Client initiates connection with INIT
      (reset! client-state (assoc @client-state :state :cookie-wait))
      (let [init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                         :chunks [{:type :init
                                   :init-tag (:local-ver-tag @client-state)
                                   :a-rwnd 100000
                                   :outbound-streams 10
                                   :inbound-streams 10
                                   :initial-tsn (:next-tsn @client-state)
                                   :params {}}]}]

        ;; Server receives INIT first time
        (handle-sctp-packet server-conn init-packet)

        ;; Server generates first INIT-ACK
        (let [init-ack-packet1 (.poll server-out)]
          (is init-ack-packet1 "Server should produce first INIT-ACK")

          ;; Server receives the exact same INIT again (e.g. retransmission by client)
          (handle-sctp-packet server-conn init-packet)

          ;; Server generates another INIT-ACK
          (let [init-ack-packet2 (.poll server-out)]
            (is init-ack-packet2 "Server should produce second INIT-ACK")

            ;; Verify that they are indeed distinct INIT-ACKs (cookie differs)
            (let [chunk1 (first (:chunks init-ack-packet1))
                  chunk2 (first (:chunks init-ack-packet2))]
              (is (not= (:cookie (:params chunk1))
                        (:cookie (:params chunk2)))
                  "Second INIT-ACK should have a different cookie than the first"))

            ;; Client proceeds using the SECOND INIT-ACK
            (handle-sctp-packet client-conn init-ack-packet2)

            (let [cookie-echo-packet (.poll client-out)]
              (is cookie-echo-packet "Client should produce COOKIE-ECHO in response to INIT-ACK2")

              ;; Server receives COOKIE-ECHO
              (handle-sctp-packet server-conn cookie-echo-packet)

              (let [cookie-ack-packet (.poll server-out)]
                (is cookie-ack-packet "Server should produce COOKIE-ACK in response to COOKIE-ECHO")

                ;; Verify server state transitioned properly
                (is (= :established (:state @server-state)) "Server should transition to established")
                (is (true? @server-opened) "Server on-open should be called")

                ;; Client receives COOKIE-ACK
                (handle-sctp-packet client-conn cookie-ack-packet)
                (is (= :established (:state @client-state)) "Client should transition to established")
                (is (true? @client-opened) "Client on-open should be called")))))))))
