(ns datachannel.sctp-checksum-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest always-sends-cookie-echo-with-non-zero-checksum-test
  (testing "Always Sends Cookie Echo With Non Zero Checksum"
    (let [packet {:src-port 5000
                  :dst-port 5001
                  :verification-tag 12345
                  :chunks [{:type :cookie-echo
                            :cookie (byte-array 10)}]}
          buf (ByteBuffer/allocate 1024)]
      (sctp/encode-packet packet buf)

      ;; Read the checksum
      (.flip buf)

      ;; Checksum is bytes 8-11 in little-endian order, but let's just
      ;; ensure it is not exactly 0.
      (.position buf 8)
      (let [checksum (.getInt buf)]
        (is (not= 0 checksum) "COOKIE ECHO packet checksum must not be zero")))))

(deftest may-send-init-ack-with-zero-checksum-test
  (testing "May Send Init Ack With Zero Checksum"
    (let [packet {:src-port 5000
                  :dst-port 5001
                  :verification-tag 1111
                  :chunks [{:type :init-ack
                            :init-tag 2222
                            :a-rwnd 100000
                            :outbound-streams 10
                            :inbound-streams 10
                            :initial-tsn 2000
                            :params {}}]}
          buf (ByteBuffer/allocate 1024)]
      (sctp/encode-packet packet buf {:zero-checksum? true})
      (.flip buf)
      (.position buf 8)
      (let [checksum (.getInt buf)]
        (is (= 0 checksum) "INIT-ACK packet checksum must be zero when zero-checksum? is true")))))

(deftest sends-cookie-ack-with-zero-checksum-test
  (testing "Sends Cookie Ack With Zero Checksum"
    (let [packet {:src-port 5000
                  :dst-port 5001
                  :verification-tag 1111
                  :chunks [{:type :cookie-ack}]}
          buf (ByteBuffer/allocate 1024)]
      (sctp/encode-packet packet buf {:zero-checksum? true})
      (.flip buf)
      (.position buf 8)
      (let [checksum (.getInt buf)]
        (is (= 0 checksum) "COOKIE ACK packet checksum must be zero when zero-checksum? is true")))))

(deftest sends-data-with-zero-checksum-test
  (testing "Sends Data With Zero Checksum"
    (let [packet {:src-port 5000
                  :dst-port 5001
                  :verification-tag 1111
                  :chunks [{:type :data
                            :flags 3
                            :tsn 1000
                            :stream-id 1
                            :seq-num 0
                            :protocol :webrtc/string
                            :payload (.getBytes "Hello" "UTF-8")}]}
          buf (ByteBuffer/allocate 1024)]
      (sctp/encode-packet packet buf {:zero-checksum? true})
      (.flip buf)
      (.position buf 8)
      (let [checksum (.getInt buf)]
        (is (= 0 checksum) "DATA packet checksum must be zero when zero-checksum? is true")))))

(deftest all-packets-after-connect-have-zero-checksum-test
  (testing "All Packets After Connect Have Zero Checksum"
    ;; Testing Heartbeat, SACK, etc...
    (let [sack-packet {:src-port 5000
                       :dst-port 5001
                       :verification-tag 1111
                       :chunks [{:type :sack
                                 :cum-tsn-ack 1000
                                 :a-rwnd 100000
                                 :gap-blocks []
                                 :duplicate-tsns []}]}
          hb-packet {:src-port 5000
                     :dst-port 5001
                     :verification-tag 1111
                     :chunks [{:type :heartbeat
                               :params {}}]}
          buf (ByteBuffer/allocate 1024)]
      (sctp/encode-packet sack-packet buf {:zero-checksum? true})
      (.flip buf)
      (.position buf 8)
      (let [checksum (.getInt buf)]
        (is (= 0 checksum) "SACK packet checksum must be zero when zero-checksum? is true"))

      (.clear buf)
      (sctp/encode-packet hb-packet buf {:zero-checksum? true})
      (.flip buf)
      (.position buf 8)
      (let [checksum (.getInt buf)]
        (is (= 0 checksum) "HEARTBEAT packet checksum must be zero when zero-checksum? is true")))))
