(ns datachannel.api-routing-table-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.api :as api]))

(deftest test-routing-table-ttl-reaper
  (testing "The routing table background reaper drops disconnected peers after 30 seconds"
    (let [opts {:port 5005}
          listener-node (api/listen! opts {})
          routing-table (:routing-table listener-node)]
      (try
        ;; Simulate an incoming packet by inserting a mock child node manually into the routing table
        (swap! routing-table assoc "mock-addr"
               {:state-atom (atom {:ice-connection-state :new
                                   :last-stun-received (- (System/currentTimeMillis) 31000)})})

        ;; Wait a little over 10 seconds for the background reaper to run its 10s cycle
        (Thread/sleep 11000)

        (is (empty? @routing-table) "The stale unverified connection should be reaped")
        (finally
          (api/close! listener-node))))))
