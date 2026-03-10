(ns datachannel.dtls-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.dtls :as dtls]))

(deftest test-fingerprint
  (testing "Fingerprint generation"
    (let [cert-data (dtls/generate-cert)]
      (is (map? cert-data))
      (is (some? (:cert cert-data)))
      (is (some? (:key cert-data)))
      (is (string? (:fingerprint cert-data)))
      (is (re-matches #"[0-9A-F]{2}(:[0-9A-F]{2})+" (:fingerprint cert-data))))))
