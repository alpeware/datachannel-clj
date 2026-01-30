(ns datachannel.stun-integration-test
  (:require [clojure.test :refer :all]
            [datachannel.stun :as stun])
  (:import [java.net InetSocketAddress]
           [java.nio.channels DatagramChannel]
           [java.nio ByteBuffer]))

(deftest test-google-stun
  (let [stun-server "stun.l.google.com"
        stun-port 19302
        channel (DatagramChannel/open)]
    (.configureBlocking channel true)
    (.connect channel (InetSocketAddress. stun-server stun-port))

    (let [req (stun/make-simple-binding-request)]
      (.write channel req))

    (let [resp-buf (ByteBuffer/allocate 1024)
          len (.read channel resp-buf)]
      (.flip resp-buf)
      (is (> len 0) "Should receive response from STUN server")

      (when (> len 0)
        (let [parsed (stun/parse-packet resp-buf)]
          (is (= 0x0101 (:type parsed)) "Should be Binding Success Response")

          (let [xor-addr-attr (get (:attributes parsed) stun/ATTR_XOR_MAPPED_ADDRESS)]
            (is xor-addr-attr "Should have XOR-MAPPED-ADDRESS attribute")
            (when xor-addr-attr
              (let [decoded (stun/decode-xor-mapped-address xor-addr-attr (:cookie parsed))]
                (is (instance? java.net.InetAddress (:address decoded)))
                (is (> (:port decoded) 0))
                (println "Public IP:" (:address decoded) "Port:" (:port decoded))))))))

    (.close channel)))
