(ns datachannel.sctp-resend-init-and-establish-connection-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest resend-init-and-establish-connection-test
  (testing "Resend Init And Establish Connection"
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
          _ (is first-init-packet "A should send INIT")
          _ (is (= :init (:type (first (:chunks first-init-packet)))))
          _ (is (= :cookie-wait (:state state-a1)))

          ;; Simulate A's INIT being lost...
          ;; Now we expire the :t1-init timer on A
          t1-timer (get-in state-a1 [:timers :t1-init])
          _ (is t1-timer "A should have started t1-init timer")
          expires-at (:expires-at t1-timer)

          res-a2 (#'core/handle-timeout state-a1 :t1-init expires-at)
          state-a2 (:new-state res-a2)
          resent-init-packet (first (:network-out res-a2))
          _ (is resent-init-packet "A should resend INIT")
          _ (is (= :init (:type (first (:chunks resent-init-packet)))))

          ;; Z receives resent INIT and replies with INIT-ACK
          res-z1 (#'core/handle-sctp-packet state-z resent-init-packet expires-at)
          state-z1 (:new-state res-z1)
          init-ack-packet (first (:network-out res-z1))
          _ (is init-ack-packet "Z should send INIT-ACK")
          _ (is (= :init-ack (:type (first (:chunks init-ack-packet)))))

          ;; A receives INIT-ACK and replies with COOKIE-ECHO
          res-a3 (#'core/handle-sctp-packet state-a2 init-ack-packet expires-at)
          state-a3 (:new-state res-a3)
          cookie-echo-packet (first (:network-out res-a3))
          _ (is cookie-echo-packet "A should send COOKIE-ECHO")
          _ (is (= :cookie-echo (:type (first (:chunks cookie-echo-packet)))))

          ;; Z receives COOKIE-ECHO and replies with COOKIE-ACK
          res-z2 (#'core/handle-sctp-packet state-z1 cookie-echo-packet expires-at)
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