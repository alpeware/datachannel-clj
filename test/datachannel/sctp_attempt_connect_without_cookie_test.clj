(ns datachannel.sctp-attempt-connect-without-cookie-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest attempt-connect-without-cookie-test
  (testing "Receiving INIT-ACK without a state cookie aborts the connection"
    (let [now-ms 1000

          ;; Initialize client state and trigger connect
          client-opts {:heartbeat-interval 30000 :max-retransmissions 10}
          client-state (core/create-connection client-opts true)
          _client-ver-tag (:local-ver-tag client-state)

          connect-event {:type :connect}
          res1 (core/handle-event client-state connect-event now-ms)
          state1 (:new-state res1)

          ;; Extract INIT tag from the client's INIT packet to use in INIT-ACK
          init-packet (first (:network-out res1))
          init-tag (get-in init-packet [:chunks 0 :init-tag])

          ;; Verify state is cookie-wait
          _ (is (= :cookie-wait (:state state1)))

          ;; Create malicious INIT-ACK packet without the state cookie
          init-ack-packet {:src-port 5000
                           :dst-port 5000
                           :verification-tag init-tag
                           :chunks [{:type :init-ack
                                     :init-tag 12345678
                                     :a-rwnd 100000
                                     :outbound-streams 10
                                     :inbound-streams 10
                                     :initial-tsn 1000
                                     :params {}}]} ;; Empty params, no cookie

          ;; Handle the malicious INIT-ACK
          res2 (core/handle-sctp-packet state1 init-ack-packet now-ms)
          state2 (:new-state res2)
          net-out (:network-out res2)
          events (:app-events res2)]

      ;; Connection must be closed
      (is (= :closed (:state state2)))

      ;; Must send an ABORT chunk
      (is (= 1 (count net-out)))
      (is (= :abort (get-in (first net-out) [:chunks 0 :type])))

      ;; Verification tag of ABORT must match the init-tag of the received INIT-ACK chunk
      (is (= 12345678 (get-in (first net-out) [:verification-tag])))

      ;; Must emit a protocol-violation error event
      (is (= 2 (count events)))
      (is (= {:type :on-error :cause :protocol-violation} (first events))))))
