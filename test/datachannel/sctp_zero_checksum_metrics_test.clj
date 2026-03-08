(ns datachannel.sctp-zero-checksum-metrics-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest zero-checksum-metrics-are-set-test
  (testing "Zero Checksum Metrics Are Set"
    (let [connection-info (@#'core/create-connection {:zero-checksum? true} true)
          conn (:connection connection-info)
          state @(:state conn)
          metrics (:metrics state)]
      (is (= true (:uses-zero-checksum metrics)) "uses-zero-checksum should be true"))

    (let [connection-info (@#'core/create-connection {:zero-checksum? false} true)
          conn (:connection connection-info)
          state @(:state conn)
          metrics (:metrics state)]
      (is (= false (:uses-zero-checksum metrics)) "uses-zero-checksum should be false"))

    (let [connection-info (@#'core/create-connection {} true)
          conn (:connection connection-info)
          state @(:state conn)
          metrics (:metrics state)]
      (is (= false (boolean (:uses-zero-checksum metrics))) "uses-zero-checksum should default to false"))))
