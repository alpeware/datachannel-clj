(ns datachannel.sctp-close-after-too-many-lost-heartbeats-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest close-connection-after-too-many-lost-heartbeats-test
  (testing "Close Connection After Too Many Lost Heartbeats"
    (let [now 1000000
          max-retries 10
          state {:state :established
                 :remote-ver-tag 1234
                 :local-ver-tag 5678
                 :next-tsn 1000
                 :ssn 0
                 :timers {:sctp/t-heartbeat {:expires-at (+ now 30000)}}
                 :heartbeat-interval 30000
                 :heartbeat-error-count 0
                 :rto-initial 1000
                 :max-retransmissions max-retries}]

      (loop [current-state state
             current-time now
             error-count 0]
        (if (<= error-count max-retries)
          ;; Advance t-heartbeat, then advance t-heartbeat-rtx
          (let [hb-time (+ current-time 30000)
                res1 (core/handle-timeout current-state :sctp/t-heartbeat hb-time)
                state-hb (:new-state res1)

                rtx-time (+ hb-time 1000)
                res2 (core/handle-timeout state-hb :sctp/t-heartbeat-rtx rtx-time)
                state-rtx (:new-state res2)]

            (if (< error-count max-retries)
              (do
                (is (= (inc error-count) (:heartbeat-error-count state-rtx)))
                (is (= :established (:state state-rtx)))
                (recur state-rtx rtx-time (inc error-count)))
              (do
                (is (= :closed (:state state-rtx)))
                (is (= 1 (count (:network-out res2))))
                (is (= :abort (-> res2 :network-out first :chunks first :type)))
                (is (= 1 (count (:app-events res2))))
                (is (= :on-error (-> res2 :app-events first :type)))
                (is (= :max-retransmissions (-> res2 :app-events first :cause))))))
          nil)))))
