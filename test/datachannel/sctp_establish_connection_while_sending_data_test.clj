(ns datachannel.sctp-establish-connection-while-sending-data-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest establish-connection-while-sending-data-test
  (testing "Establish Connection While Sending Data"
    (let [now 1000
          client-state {:state :closed :local-ver-tag 1111 :initial-tsn 1000 :metrics {}}
          server-state {:state :closed :local-ver-tag 2222 :initial-tsn 2000 :metrics {} :next-tsn 2000}

          ;; 1. Client connects
          res-c1 (core/handle-event client-state {:type :connect} now)
          client-cw (:new-state res-c1)
          init-packet (first (:network-out res-c1))]
      (is (= :cookie-wait (:state client-cw)))
      (is init-packet "Client emits INIT packet")

      ;; 2. Client sends data while in :cookie-wait
      (let [res-c2 (core/send-data client-cw (.getBytes "Test Data" "UTF-8") 1 :webrtc/string now)
            client-cw2 (:new-state res-c2)]
        (is (empty? (:network-out res-c2)) "Client does not send data immediately")
        (is (= 1 (count (:send-queue (get-in client-cw2 [:streams 1])))) "Data buffered in stream send-queue")

        ;; 3. Server handles INIT, replies with INIT-ACK
        (let [res-s1 (@#'core/handle-sctp-packet server-state init-packet now)
              server-ia (:new-state res-s1)
              init-ack-packet (first (:network-out res-s1))]
          (is (= :cookie-wait (:state server-ia)))
          (is init-ack-packet "Server emits INIT-ACK packet")

          ;; 4. Client handles INIT-ACK, replies with COOKIE-ECHO
          (let [res-c3 (@#'core/handle-sctp-packet client-cw2 init-ack-packet now)
                client-ce (:new-state res-c3)
                cookie-echo-packet (first (:network-out res-c3))]
            (is (= :cookie-echoed (:state client-ce)))
            (is cookie-echo-packet "Client emits COOKIE-ECHO packet")
            (is (empty? (rest (:network-out res-c3))) "Client does NOT bundle data with COOKIE-ECHO in this implementation yet, nor sends data separately")

            ;; 5. Server handles COOKIE-ECHO, replies with COOKIE-ACK, becomes established
            (let [res-s2 (@#'core/handle-sctp-packet server-ia cookie-echo-packet now)
                  server-est (:new-state res-s2)
                  cookie-ack-packet (first (:network-out res-s2))]
              (is (= :established (:state server-est)))
              (is cookie-ack-packet "Server emits COOKIE-ACK packet")

              ;; 6. Client handles COOKIE-ACK, becomes established, and NOW sends the buffered DATA
              (let [res-c4 (@#'core/handle-sctp-packet client-ce cookie-ack-packet now)
                    client-est (:new-state res-c4)
                    data-packets (:network-out res-c4)]
                (is (= :established (:state client-est)))
                (is (= 1 (count data-packets)) "Client sends the buffered data packet after COOKIE-ACK")

                ;; 7. Server handles the DATA packet, triggers app event, and starts SACK timer
                (let [data-packet (first data-packets)
                      res-s3 (@#'core/handle-sctp-packet server-est data-packet now)
                      server-est2 (:new-state res-s3)
                      events (:app-events res-s3)

                      ;; Advance time to trigger delayed SACK
                      res-s3-timeout (core/handle-timeout server-est2 :sctp/t-delayed-sack (+ now 200))
                      sack-packet (first (:network-out res-s3-timeout))]

                  (is sack-packet "Server emits SACK after timeout")
                  (is (= 1 (count events)))
                  (let [event (first events)]
                    (is (= :on-message (:type event)))
                    (is (= 1 (:stream-id event)))
                    (is (= "Test Data" (String. ^bytes (:payload event) "UTF-8")))))))))))))
