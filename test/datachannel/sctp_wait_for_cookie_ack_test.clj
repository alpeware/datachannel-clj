(ns datachannel.sctp-wait-for-cookie-ack-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core])
  (:import [java.util.concurrent LinkedBlockingQueue]))

(deftest sctp-doesnt-send-more-packets-until-cookie-ack-has-been-received
  (let [initial-state {:state :cookie-wait
                       :local-ver-tag 1234
                       :remote-ver-tag 5678
                       :next-tsn 100
                       :ssn 0
                       :timers {}
                       :heartbeat-interval 30000}
        now-ms 1000]

    (testing "Transition to :cookie-echoed on :init-ack"
      (let [init-ack-packet {:src-port 5000 :dst-port 5000 :verification-tag 1234
                             :chunks [{:type :init-ack
                                       :init-tag 5678
                                       :a-rwnd 100000
                                       :outbound-streams 10
                                       :inbound-streams 10
                                       :initial-tsn 200
                                       :params {:cookie (byte-array [1 2 3])}}]}
            {:keys [new-state network-out]} (core/handle-sctp-packet initial-state init-ack-packet now-ms)]

        (is (= :cookie-echoed (:state new-state)))
        (is (= 1 (count network-out)))
        (is (= :cookie-echo (:type (first (:chunks (first network-out))))))

        (testing "Does not send data packet if state is not :established"
          (let [mock-connection {:state (atom new-state)
                                 :sctp-out (LinkedBlockingQueue.)}
                payload (.getBytes "Test Data" "UTF-8")]

            ;; Try to send data while in :cookie-echoed state
            (core/send-data mock-connection payload 0 :webrtc/string)

            ;; Queue should be empty because we shouldn't send it yet
            (is (zero? (.size (:sctp-out mock-connection))))

            ;; But state's tx-queue should contain the message
            (is (= 1 (count (:tx-queue @(:state mock-connection)))))
            (is (= :data (:type (first (:chunks (:packet (first (:tx-queue @(:state mock-connection)))))))))

            (testing "Sends queued packets when receiving :cookie-ack"
              (let [cookie-ack-packet {:src-port 5000 :dst-port 5000 :verification-tag 1234
                                       :chunks [{:type :cookie-ack}]}
                    {:keys [new-state network-out]} (core/handle-sctp-packet @(:state mock-connection) cookie-ack-packet now-ms)]

                (is (= :established (:state new-state)))
                ;; We expect 1 packet in network out corresponding to the drained DATA chunk
                (is (= 1 (count network-out)))
                (let [sent-packet (first network-out)]
                  (is (= :data (:type (first (:chunks sent-packet))))))))))))))