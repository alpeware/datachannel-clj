(ns datachannel.sctp-stateless-init-test
  (:require [clojure.test :refer [deftest is]]
            [datachannel.core :as core]))

(deftest stateless-init-test
  (let [;; 1. Initialize server state
        server-state (core/create-connection {} false)
        now-ms 1000

        ;; 2. Craft an incoming INIT chunk
        init-chunk {:type :init
                    :init-tag 12345
                    :a-rwnd 100000
                    :outbound-streams 10
                    :inbound-streams 10
                    :initial-tsn 9999
                    :params {}}
        init-packet {:src-port 5000
                     :dst-port 5001
                     :verification-tag 0 ; INIT must have vtag 0
                     :chunks [init-chunk]}

        ;; 3. Process the INIT packet
        res-init (core/handle-sctp-packet server-state init-packet now-ms)
        state-after-init (:new-state res-init)
        network-out (:network-out res-init)
        init-ack-packet (first network-out)
        init-ack-chunk (first (:chunks init-ack-packet))]

    ;; Verify that the server sends an INIT-ACK
    (is (= 1 (count network-out)))
    (is (= :init-ack (:type init-ack-chunk)))

    ;; Verify that the server state did NOT transition to :cookie-wait or save peer parameters
    ;; It should remain essentially untouched except for metrics/ports
    (is (not= :cookie-wait (:state state-after-init)))
    (is (= 0 (:remote-ver-tag state-after-init)))
    (is (nil? (:remote-tsn state-after-init)))

    ;; Verify the signed cookie is present in the INIT-ACK
    (let [cookie (get-in init-ack-chunk [:params :cookie])]
      (is (some? cookie))
      (is (> (alength ^bytes cookie) 0))

      ;; 4. Simulate a valid COOKIE-ECHO response
      (let [cookie-echo-chunk {:type :cookie-echo
                               :cookie cookie}
            cookie-echo-packet {:src-port 5001
                                :dst-port 5000
                                :verification-tag (:local-ver-tag server-state)
                                :chunks [cookie-echo-chunk]}
            res-cookie-echo (core/handle-sctp-packet state-after-init cookie-echo-packet now-ms)
            state-after-cookie-echo (:new-state res-cookie-echo)]

        ;; Verify that processing a valid COOKIE-ECHO transitions the state to :established
        ;; and correctly unpacks the peer parameters that were hidden in the cookie.
        (is (= :established (:state state-after-cookie-echo)))
        (is (= 12345 (:remote-ver-tag state-after-cookie-echo)))
        (is (= 9998 (:remote-tsn state-after-cookie-echo))) ; 9999 - 1

        ;; Verify it sends a COOKIE-ACK
        (let [cookie-ack-packet (first (:network-out res-cookie-echo))
              cookie-ack-chunk (first (:chunks cookie-ack-packet))]
          (is (= :cookie-ack (:type cookie-ack-chunk)))))

      ;; 5. Simulate an invalid COOKIE-ECHO response (tampered)
      (let [tampered-cookie (byte-array (alength ^bytes cookie))
            _ (System/arraycopy cookie 0 tampered-cookie 0 (alength ^bytes cookie))
            ;; Flip a bit in the tampered cookie
            _ (aset-byte tampered-cookie 0 (unchecked-byte (bit-xor (aget tampered-cookie 0) 0x01)))

            invalid-cookie-echo-chunk {:type :cookie-echo
                                       :cookie tampered-cookie}
            invalid-cookie-echo-packet {:src-port 5001
                                        :dst-port 5000
                                        :verification-tag (:local-ver-tag server-state)
                                        :chunks [invalid-cookie-echo-chunk]}
            res-invalid-cookie-echo (core/handle-sctp-packet state-after-init invalid-cookie-echo-packet now-ms)
            state-after-invalid-cookie-echo (:new-state res-invalid-cookie-echo)]

        ;; Verify that processing an invalid COOKIE-ECHO does NOT transition the state
        ;; It should ignore the packet.
        (is (not= :established (:state state-after-invalid-cookie-echo)))
        (is (nil? (:remote-ver-tag state-after-invalid-cookie-echo)))
        (is (empty? (:network-out res-invalid-cookie-echo)))))))
