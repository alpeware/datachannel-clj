(ns datachannel.sctp-negotiated-streams-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest exposes-the-number-of-negotiated-streams-test
  (testing "Exposes The Number Of Negotiated Streams"
    (let [client-options {:announced-maximum-incoming-streams 12
                          :announced-maximum-outgoing-streams 45}
          server-options {:announced-maximum-incoming-streams 23
                          :announced-maximum-outgoing-streams 34}

          client-state (atom {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :state :closed
                              :options client-options
                              :metrics {:tx-packets 0 :rx-packets 0 :tx-bytes 0 :rx-bytes 0 :retransmissions 0 :unacked-data 0}})
          client-out (java.util.concurrent.LinkedBlockingQueue.)
          client-conn {:state client-state
                       :sctp-out client-out
                       :on-open (atom nil)}

          server-state (atom {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0 :state :closed
                              :options server-options
                              :metrics {:tx-packets 0 :rx-packets 0 :tx-bytes 0 :rx-bytes 0 :retransmissions 0 :unacked-data 0}})
          server-out (java.util.concurrent.LinkedBlockingQueue.)
          server-conn {:state server-state
                       :sctp-out server-out
                       :on-open (atom nil)}

          handle-sctp-packet (fn [c p]
                               (when (and p c)
                                 (let [state-map @(:state c)
                                       res (@#'core/handle-sctp-packet state-map p (System/currentTimeMillis))
                                       next-state (:new-state res)
                                       network-out (:network-out res)
                                       app-events (:app-events res)]
                                   (reset! (:state c) next-state)
                                   (doseq [out network-out] (.offer (:sctp-out c) out))
                                   (doseq [evt app-events]
                                     (case (:type evt)
                                       :on-message (when-let [cb (:on-message c)] (when (and cb @cb) (@cb (:payload evt))))
                                       :on-data (when-let [cb (:on-data c)] (when (and cb @cb) (@cb (assoc evt :payload (:payload evt) :stream-id (:stream-id evt)))))
                                       :on-open (when-let [cb (:on-open c)] (when (and cb @cb) (@cb)))
                                       :on-error (when-let [cb (:on-error c)] (when (and cb @cb) (@cb (:causes evt))))
                                       :on-close (when-let [cb (:on-close c)] (when (and cb @cb) (@cb)))
                                       nil)))))]

      ;; 1. Client initiates connection with INIT
      (reset! client-state (assoc @client-state :state :cookie-wait))
      (let [init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                         :chunks [{:type :init
                                   :init-tag (:local-ver-tag @client-state)
                                   :a-rwnd 100000
                                   :outbound-streams 45
                                   :inbound-streams 12
                                   :initial-tsn (:next-tsn @client-state)
                                   :params {}}]}]

        ;; Server receives INIT
        (handle-sctp-packet server-conn init-packet)

        ;; Server generates INIT-ACK
        (let [init-ack-packet (.poll server-out)]
          (is init-ack-packet "Server should produce INIT-ACK")

          ;; Client receives INIT-ACK
          (handle-sctp-packet client-conn init-ack-packet)

          ;; Metrics assertions for Server
          (is (= 23 (:negotiated-maximum-incoming-streams (:metrics @server-state))))
          (is (= 12 (:negotiated-maximum-outgoing-streams (:metrics @server-state))))

          ;; Metrics assertions for Client
          (is (= 12 (:negotiated-maximum-incoming-streams (:metrics @client-state))))
          (is (= 23 (:negotiated-maximum-outgoing-streams (:metrics @client-state)))))))))
