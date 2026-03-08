(ns datachannel.sctp-send-many-api-method-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest send-many-api-method-test
  (testing "Send Many Api Method"
    (let [now-ms 1000
          state-a {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed}
          state-z {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed}

          ;; A connects: sends INIT
          state-a1 (merge state-a {:state :cookie-wait :init-tag 1111})
          init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                       :chunks [{:type :init :init-tag 1111 :a-rwnd 100000
                                 :outbound-streams 10 :inbound-streams 10
                                 :initial-tsn 1000 :params {}}]}

          ;; Z receives INIT and replies with INIT-ACK
          res-z1 (@#'core/handle-sctp-packet state-z init-packet now-ms)
          state-z1 (:new-state res-z1)
          init-ack-packet (first (:network-out res-z1))

          ;; A receives INIT-ACK and replies with COOKIE-ECHO
          res-a1 (@#'core/handle-sctp-packet state-a1 init-ack-packet now-ms)
          state-a2 (:new-state res-a1)
          cookie-echo-packet (first (:network-out res-a1))

          ;; Z receives COOKIE-ECHO and replies with COOKIE-ACK
          res-z2 (@#'core/handle-sctp-packet state-z1 cookie-echo-packet now-ms)
          state-z2 (:new-state res-z2)
          cookie-ack-packet (first (:network-out res-z2))

          ;; A receives COOKIE-ACK
          res-a2 (@#'core/handle-sctp-packet state-a2 cookie-ack-packet now-ms)
          state-a3 (:new-state res-a2)]

      ;; Now both A and Z are in the :established state.

      ;; A sends two messages sequentially to different streams
      (let [payload1 (.getBytes "hello" "UTF-8")
            payload2 (.getBytes "world" "UTF-8")
            res-a3 (core/send-data state-a3 payload1 1 :webrtc/string now-ms)
            state-a4 (:new-state res-a3)
            data-packets-1 (:network-out res-a3)
            res-a4 (core/send-data state-a4 payload2 2 :webrtc/string now-ms)
            state-a5 (:new-state res-a4)
            data-packets-2 (:network-out res-a4)
            all-data-packets (concat data-packets-1 data-packets-2)]

        ;; Z receives the DATA messages sequentially to simulate packet arrivals
        ;; NOTE: we handle them manually and collect app events
        (let [res-z3-1 (@#'core/handle-sctp-packet state-z2 (first data-packets-1) now-ms)
              res-z3-2 (@#'core/handle-sctp-packet (:new-state res-z3-1) (first data-packets-2) now-ms)
              app-events (concat (:app-events res-z3-1) (:app-events res-z3-2))]

          ;; Z should have emitted two :on-message events
          (let [messages (filter #(= :on-message (:type %)) app-events)]
            (is (= 2 (count messages)) "Z should have received two messages")
            (when (= 2 (count messages))
              (is (= "hello" (String. ^bytes (:payload (first messages)) "UTF-8")))
              (is (= 1 (:stream-id (first messages))))
              (is (= "world" (String. ^bytes (:payload (second messages)) "UTF-8")))
              (is (= 2 (:stream-id (second messages)))))))))))
