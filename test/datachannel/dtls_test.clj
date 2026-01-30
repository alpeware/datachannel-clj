(ns datachannel.dtls-test
  (:require [clojure.test :refer :all]
            [datachannel.dtls :as dtls])
  (:import [javax.net.ssl SSLContext SSLEngine SSLEngineResult$HandshakeStatus SSLEngineResult$Status]
           [java.nio ByteBuffer]))

(deftest test-fingerprint
  (testing "Fingerprint generation"
    (let [cert-data (dtls/generate-cert)]
      (is (map? cert-data))
      (is (some? (:cert cert-data)))
      (is (some? (:key cert-data)))
      (is (string? (:fingerprint cert-data)))
      (is (re-matches #"[0-9A-F]{2}(:[0-9A-F]{2})+" (:fingerprint cert-data))))))
