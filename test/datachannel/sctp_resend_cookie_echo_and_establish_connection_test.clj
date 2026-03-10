(ns datachannel.sctp-resend-cookie-echo-and-establish-connection-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest resend-cookie-echo-and-establish-connection-test
  (testing "Resend Cookie Echo And Establish Connection"
    (let [now-ms 1000
          state-a {:local-ver-tag 12345
                   :initial-tsn 100
                   :timers {}
                   :metrics {:tx-packets 0 :rx-packets 0}
                   :options {:t1-init-timeout 1000}}
          state-z {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed}

          ;; A connects: sends INIT and starts t1-init timer
          connect-event {:type :connect}
          res-a1 (#'core/handle-event state-a connect-event now-ms)
          state-a1 (:new-state res-a1)
          first-init-packet (first (:network-out res-a1))

          ;; Z receives INIT and replies with INIT-ACK
          res-z1 (#'core/handle-sctp-packet state-z first-init-packet now-ms)
          state-z1 (:new-state res-z1)
          init-ack-packet (first (:network-out res-z1))

          ;; A receives INIT-ACK and replies with COOKIE-ECHO
          res-a2 (#'core/handle-sctp-packet state-a1 init-ack-packet now-ms)
          state-a2 (:new-state res-a2)
          first-cookie-echo-packet (first (:network-out res-a2))
          _ (is first-cookie-echo-packet "A should send COOKIE-ECHO")
          _ (is (= :cookie-echo (:type (first (:chunks first-cookie-echo-packet)))))
          _ (is (= :cookie-echoed (:state state-a2)))

          ;; Simulate A's COOKIE-ECHO being lost...
          ;; Now we expire the :sctp/t1-cookie timer on A
          t1-timer (get-in state-a2 [:timers :sctp/t1-cookie])
          _ (is t1-timer "A should have started t1-cookie timer")
          expires-at (:expires-at t1-timer)

          res-a3 (#'core/handle-timeout state-a2 :sctp/t1-cookie expires-at)
          state-a3 (:new-state res-a3)
          resent-cookie-echo-packet (first (:network-out res-a3))
          _ (is resent-cookie-echo-packet "A should resend COOKIE-ECHO")
          _ (is (= :cookie-echo (:type (first (:chunks resent-cookie-echo-packet)))))

          ;; Z receives resent COOKIE-ECHO and replies with COOKIE-ACK
          res-z2 (#'core/handle-sctp-packet state-z1 resent-cookie-echo-packet expires-at)
          state-z2 (:new-state res-z2)
          cookie-ack-packet (first (:network-out res-z2))
          _ (is cookie-ack-packet "Z should send COOKIE-ACK")
          _ (is (= :cookie-ack (:type (first (:chunks cookie-ack-packet)))))
          _ (is (= :established (:state state-z2)) "Z should be established")
          _ (is (some #(= :on-open (:type %)) (:app-events res-z2)) "Z should have fired on-open event")

          ;; A receives COOKIE-ACK
          res-a4 (#'core/handle-sctp-packet state-a3 cookie-ack-packet expires-at)
          state-a4 (:new-state res-a4)]

      (is (= :established (:state state-a4)) "A should be established")
      (is (some #(= :on-open (:type %)) (:app-events res-a4)) "A should have fired on-open event")
      (is (empty? (:network-out res-a4)) "A should not send more packets"))))
