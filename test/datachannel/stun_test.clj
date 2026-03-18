(ns datachannel.stun-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.stun :as stun])
  (:import [java.nio ByteBuffer]))

(deftest test-decode-xor-mapped-address-ipv4
  (let [cookie stun/magic-cookie
        original-port 12345
        original-addr (byte-array [(unchecked-byte 192) (unchecked-byte 168) (unchecked-byte 0) (unchecked-byte 1)])

        ;; XORed port
        xport (bit-xor original-port (bit-shift-right cookie 16))

        ;; XORed address
        cookie-bytes (ByteBuffer/allocate 4)
        _ (.putInt cookie-bytes cookie)
        _ (.flip cookie-bytes)
        magic-bytes (.array cookie-bytes)
        xor-addr (byte-array 4)
        _ (dotimes [i 4]
            (aset xor-addr i (byte (bit-xor (aget original-addr i) (aget magic-bytes i)))))

        ;; Construct attribute value: reserved (1) + family (1) + xport (2) + xor-addr (4)
        val-buf (ByteBuffer/allocate 8)
        _ (.put val-buf (byte 0))
        _ (.put val-buf (byte 0x01)) ;; IPv4
        _ (.putShort val-buf (short xport))
        _ (.put val-buf xor-addr)
        _ (.flip val-buf)
        val-bytes (byte-array 8)
        _ (.get val-buf val-bytes)
        decoded (stun/decode-xor-mapped-address val-bytes cookie)]
    (is (= 0x01 (:family decoded)))
    (is (= original-port (:port decoded)))
    (is (= "192.168.0.1" (.getHostAddress (:address decoded))))))

(deftest test-decode-xor-mapped-address-unsupported-family
  (let [val-bytes (byte-array [0 0x02 0 0 0 0 0 0])] ;; Family 0x02 (IPv6)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Only IPv4 supported for now"
                          (stun/decode-xor-mapped-address val-bytes stun/magic-cookie)))))

(deftest test-message-integrity-validation
  (let [password "test-password"
        local-ufrag "local"
        remote-ufrag "remote"
        valid-req (stun/make-binding-request local-ufrag remote-ufrag password)
        peer-addr (java.net.InetSocketAddress. "127.0.0.1" 12345)
        conn {:ice-pwd password}
        wrong-conn {:ice-pwd "wrong-password"}]

    (testing "Valid MESSAGE-INTEGRITY"
      (let [result (stun/handle-packet valid-req peer-addr conn)]
        (is (contains? result :response) "Should yield a response for a valid request")))

    (testing "Invalid MESSAGE-INTEGRITY (wrong password)"
      (.position valid-req 0)
      (let [result (stun/handle-packet valid-req peer-addr wrong-conn)]
        (is (empty? result) "Should not yield a response for an invalid password")))

    (testing "Missing MESSAGE-INTEGRITY"
      (let [simple-req (stun/make-simple-binding-request)
            result (stun/handle-packet simple-req peer-addr conn)]
        (is (empty? result) "Should not yield a response if MESSAGE-INTEGRITY is missing")))))
