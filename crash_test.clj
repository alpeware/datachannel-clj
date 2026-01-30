(ns crash-test
  (:require [datachannel.core :as dc])
  (:import [java.net InetSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels DatagramChannel]))

(defn test-crash []
  (let [port 16000
        server (dc/listen port)
        client-channel (DatagramChannel/open)]
    (.connect client-channel (InetSocketAddress. "127.0.0.1" port))

    (println "Sending first garbage packet...")
    (let [buf (ByteBuffer/wrap (byte-array [0 0 0 0]))]
      (.write client-channel buf))

    (Thread/sleep 100)

    (println "Sending second garbage packet...")
    (let [buf (ByteBuffer/wrap (byte-array [0 0 0 0]))]
      (.write client-channel buf))

    (println "Waiting...")
    (Thread/sleep 500)

    (println "Test finished.")
    (System/exit 0)))

(test-crash)
