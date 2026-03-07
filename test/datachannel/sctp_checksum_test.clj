(ns datachannel.sctp-checksum-test
  (:require [clojure.test :refer :all]
            [datachannel.sctp :as sctp])
  (:import [java.nio ByteBuffer]))

(deftest always-sends-init-with-non-zero-checksum-test
  (testing "Always Sends Init With Non Zero Checksum"
    (let [packet {:src-port 5000
                  :dst-port 5001
                  :verification-tag 0
                  :chunks [{:type :init
                            :init-tag 1111
                            :a-rwnd 100000
                            :outbound-streams 10
                            :inbound-streams 10
                            :initial-tsn 1000
                            :params {}}]}
          buf (ByteBuffer/allocate 1024)]
      (sctp/encode-packet packet buf)

      ;; Read the checksum
      (.flip buf)

      ;; Checksum is bytes 8-11 in little-endian order, but let's just
      ;; ensure it is not exactly 0.
      (.position buf 8)
      (let [checksum (.getInt buf)]
        (is (not= 0 checksum) "INIT packet checksum must not be zero")))))
