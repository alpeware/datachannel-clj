(ns datachannel.stun-test
  (:require [clojure.test :refer [deftest is]]
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
