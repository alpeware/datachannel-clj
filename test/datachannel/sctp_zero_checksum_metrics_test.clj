(ns datachannel.sctp-zero-checksum-metrics-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest zero-checksum-metrics-are-set-test
  (testing "Zero Checksum Metrics Are Set"
    (let [state (@#'core/create-connection {:zero-checksum? true} true)
          metrics (:metrics state)]
      (is (= true (:uses-zero-checksum metrics)) "uses-zero-checksum should be true"))

    (let [state (@#'core/create-connection {:zero-checksum? false} true)
          metrics (:metrics state)]
      (is (= false (:uses-zero-checksum metrics)) "uses-zero-checksum should be false"))

    (let [state (@#'core/create-connection {} true)
          metrics (:metrics state)]
      (is (= false (boolean (:uses-zero-checksum metrics))) "uses-zero-checksum should default to false"))))
