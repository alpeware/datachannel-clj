(ns datachannel.sctp-robustness-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest tsn-wraparound-test
  (testing "TSN wraparound handling"
    (let [state (atom {:remote-tsn 4294967295 :remote-ver-tag 123})
          connection {:state state
                      :sctp-out (java.util.concurrent.LinkedBlockingQueue.)
                      :on-message (atom nil)
                      :on-data (atom nil)}
          ;; Accessing private handle-sctp-packet for testing
          handle-sctp-packet #'core/handle-sctp-packet
          packet {:src-port 5000 :dst-port 5000
                  :chunks [{:type :data :tsn 0 :protocol :webrtc/string :payload (byte-array 0)}]}]

      (handle-sctp-packet packet connection)
      (is (= 0 (:remote-tsn @state)) "TSN 0 should be considered newer than 4294967295")

      (handle-sctp-packet {:src-port 5000 :dst-port 5000
                           :chunks [{:type :data :tsn 10 :protocol :webrtc/string :payload (byte-array 0)}]}
                          connection)
      (is (= 10 (:remote-tsn @state)) "TSN 10 should be considered newer than 0")

      (handle-sctp-packet {:src-port 5000 :dst-port 5000
                           :chunks [{:type :data :tsn 5 :protocol :webrtc/string :payload (byte-array 0)}]}
                          connection)
      (is (= 10 (:remote-tsn @state)) "Old TSN 5 should NOT update remote-tsn (still 10)"))))

(deftest unordered-delivery-test
  (testing "Unordered delivery Head-of-Line blocking"
    (let [received (atom [])
          state (atom {:remote-tsn 0 :remote-ver-tag 123})
          connection {:state state
                      :sctp-out (java.util.concurrent.LinkedBlockingQueue.)
                      :on-message (atom (fn [payload] (swap! received conj (String. ^bytes payload))))
                      :on-data (atom nil)}
          handle-sctp-packet #'core/handle-sctp-packet]

      ;; In unordered delivery, if we receive TSN 2 before TSN 1, it should still be delivered immediately.
      ;; Note: Current implementation in core.clj delivers EVERY data chunk immediately
      ;; as long as it's not a duplicate (actually it delivers everything right now,
      ;; it just updates remote-tsn if it's newer).

      (handle-sctp-packet {:src-port 5000 :dst-port 5000
                           :chunks [{:type :data :tsn 2 :protocol :webrtc/string
                                     :payload (.getBytes "Second") :unordered true}]}
                          connection)

      (is (= ["Second"] @received) "TSN 2 should be delivered even if TSN 1 is missing (unordered)")

      (handle-sctp-packet {:src-port 5000 :dst-port 5000
                           :chunks [{:type :data :tsn 1 :protocol :webrtc/string
                                     :payload (.getBytes "First") :unordered true}]}
                          connection)

      (is (= ["Second" "First"] @received) "TSN 1 should be delivered when it arrives"))))
