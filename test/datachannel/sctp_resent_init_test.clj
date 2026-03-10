(ns datachannel.sctp-resent-init-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest resent-init-has-same-parameters-test
  (testing "Resent Init Has Same Parameters"
    (let [state {:local-ver-tag 12345
                 :initial-tsn 100
                 :timers {}
                 :metrics {:tx-packets 0 :rx-packets 0}}
          connect-event {:type :connect}
          now-ms 1000

          ;; Trigger connect
          res1 (#'core/handle-event state connect-event now-ms)
          new-state (:new-state res1)
          network-out (:network-out res1)
          _ (is (= :cookie-wait (:state new-state)))
          _ (is (= 1 (count network-out)))
          first-init (first network-out)
          _ (is (= :init (:type (first (:chunks first-init)))))

          ;; Now simulate t1-init timeout
          timer-id :sctp/t1-init
          t1-timer (get-in new-state [:timers timer-id])
          expires-at (:expires-at t1-timer)
          timeout-res (#'core/handle-timeout new-state timer-id expires-at)
          _ (is (= 1 (count (:network-out timeout-res))))
          second-init (first (:network-out timeout-res))]
      (is (= :init (:type (first (:chunks second-init)))))

      ;; Ensure parameters match exactly
      (is (= (first (:chunks first-init))
             (first (:chunks second-init)))
          "Resent INIT must have the same parameters as the original INIT"))))
