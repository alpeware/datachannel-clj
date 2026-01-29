(ns datachannel.dtls-test
  (:require [clojure.test :refer :all]
            [datachannel.dtls :as dtls])
  (:import [javax.net.ssl SSLContext SSLEngine]))

(deftest test-fingerprint
  (testing "Fingerprint generation"
    (let [cert-data (dtls/generate-cert)]
      (is (map? cert-data))
      (is (some? (:cert cert-data)))
      (is (some? (:key cert-data)))
      (is (string? (:fingerprint cert-data)))
      (is (re-matches #"[0-9A-F]{2}(:[0-9A-F]{2})+" (:fingerprint cert-data))))))

(deftest test-ssl-context
  (testing "SSL Context creation"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))]
      (is (instance? SSLContext ctx)))))

(deftest test-engine-creation
  (testing "SSLEngine creation"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
          engine (dtls/create-engine ctx true)]
      (is (instance? SSLEngine engine))
      (is (.getUseClientMode engine)))))
