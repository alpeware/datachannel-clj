(ns datachannel.enforce-dtls-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as dc]
            [datachannel.sctp :as sctp])
  (:import [java.nio ByteBuffer]))

(deftest enforce-dtls-test
  (testing "Raw SCTP packets are ignored and do not bypass DTLS"
    (let [state (dc/create-connection {} true)
          now-ms (System/currentTimeMillis)
          raw-sctp-packet {:src-port 5000 :dst-port 5001 :verification-tag 0 :chunks [{:type :init :init-tag 123 :a-rwnd 1000 :outbound-streams 10 :inbound-streams 10 :initial-tsn 0 :params []}]}
          buf (ByteBuffer/allocateDirect 1500)
          _ (sctp/encode-packet raw-sctp-packet buf {:zero-checksum? false})
          _ (.flip buf)
          bytes-arr (byte-array (.remaining buf))
          _ (.get buf bytes-arr)
          res (dc/handle-receive state bytes-arr now-ms nil)]
      ;; The state should remain unmodified, and no output should be generated.
      (is (= (:new-state res) state))
      (is (empty? (:network-out res)))
      (is (empty? (:app-events res))))))
