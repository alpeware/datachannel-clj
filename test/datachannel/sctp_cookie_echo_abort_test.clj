(ns datachannel.sctp-cookie-echo-abort-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest resending-cookie-echo-too-many-times-aborts-test
  (testing "Resending Cookie Echo Too Many Times Aborts"
    (let [client-state (atom {:remote-ver-tag 0 :local-ver-tag 1111 :next-tsn 100 :ssn 0 :state :cookie-wait :timers {}})
          client-out (java.util.concurrent.LinkedBlockingQueue.)
          client-opened (atom false)
          client-errors (atom [])
          client-conn {:state client-state
                       :sctp-out client-out
                       :on-open (atom (fn [] (reset! client-opened true)))
                       :on-error (atom (fn [causes] (swap! client-errors concat causes)))}

          server-state (atom {:remote-ver-tag 0 :local-ver-tag 2222 :next-tsn 200 :ssn 0 :timers {}})
          server-out (java.util.concurrent.LinkedBlockingQueue.)
          server-opened (atom false)
          server-conn {:state server-state
                       :sctp-out server-out
                       :on-open (atom (fn [] (reset! server-opened true)))}

          handle-sctp-packet (fn [p c]
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
      (let [init-packet {:src-port 5000 :dst-port 5000 :verification-tag 0
                         :chunks [{:type :init
                                   :init-tag (:local-ver-tag @client-state)
                                   :a-rwnd 100000
                                   :outbound-streams 10
                                   :inbound-streams 10
                                   :initial-tsn (:next-tsn @client-state)
                                   :params {}}]}]
        ;; Server handles INIT
        (handle-sctp-packet init-packet server-conn))

      ;; 2. Server generates INIT-ACK
      (let [init-ack-packet (.poll server-out)]
        (is init-ack-packet "Server should produce INIT-ACK")
        (is (= :init-ack (-> init-ack-packet :chunks first :type)))

        ;; 3. Client processes INIT-ACK and generates COOKIE-ECHO
        (handle-sctp-packet init-ack-packet client-conn))

      (let [cookie-echo-packet (.poll client-out)]
        (is cookie-echo-packet "Client should produce COOKIE-ECHO")
        (is (= :cookie-echo (-> cookie-echo-packet :chunks first :type)))

        ;; Client state should now have :t1-init timer for the COOKIE-ECHO packet
        (is (some? (get-in @client-state [:timers :t1-init])) "Client should set t1-init timer for COOKIE-ECHO")

        ;; Simulate timeout expirations repeatedly until max-retransmissions is reached (8 retries)
        (let [now (System/currentTimeMillis)]
          (loop [retries 0
                 current-now now]
            (if (< retries 8)
              (let [timer (get-in @client-state [:timers :t1-init])
                    ;; Fast forward time
                    expired-now (:expires-at timer)
                    result (core/handle-timeout @client-state :t1-init expired-now)]
                (reset! client-state (:new-state result))
                (is (= 1 (count (:network-out result))))
                (is true)
                (recur (inc retries) expired-now))

              ;; Finally, the 9th expiration should abort
              (let [timer (get-in @client-state [:timers :t1-init])
                    expired-now (:expires-at timer)
                    result (core/handle-timeout @client-state :t1-init expired-now)]
                (reset! client-state (:new-state result))
                (is (= 1 (count (:app-events result))))
                (let [effect (first (:app-events result))]
                  (is (= :on-error (:type effect)))
                  (is (= :max-retransmissions (:cause effect)))
                  ;; Check state is closed
                  (is (= :closed (:state @client-state)))
                  (is (nil? (get-in @client-state [:timers :t1-init]))))))))))))