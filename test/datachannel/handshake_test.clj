(ns datachannel.handshake-test
  (:require [clojure.test :refer :all]
            [datachannel.dtls :as dtls])
  (:import [java.nio ByteBuffer]
           [javax.net.ssl SSLEngine SSLEngineResult$HandshakeStatus]))

(defn- run-handshake [client-engine server-engine]
  (let [client-out (ByteBuffer/allocate 65536)
        server-out (ByteBuffer/allocate 65536)
        client-in (ByteBuffer/allocate 65536)
        server-in (ByteBuffer/allocate 65536)
        max-loops 100]

    ;; Initialize buffers to read mode (empty)
    (.flip client-in)
    (.flip server-in)

    (.beginHandshake client-engine)
    (.beginHandshake server-engine)

    (loop [i 0]
      (if (> i max-loops)
        (throw (Exception. "Handshake failed to complete in max loops"))
        (let [client-status (.getHandshakeStatus client-engine)
              server-status (.getHandshakeStatus server-engine)]

          (if (and (= client-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                   (= server-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING))
            :success
            (do
              ;; Run client step
              (let [res-c (dtls/handshake client-engine client-in client-out)
                    packets-c (:packets res-c)]

                ;; Append output to server input
                (.compact server-in)
                (doseq [p packets-c]
                  (.put server-in (ByteBuffer/wrap p)))
                (.flip server-in))

              ;; Run server step
              (let [res-s (dtls/handshake server-engine server-in server-out)
                    packets-s (:packets res-s)]

                ;; Append output to client input
                (.compact client-in)
                (doseq [p packets-s]
                  (.put client-in (ByteBuffer/wrap p)))
                (.flip client-in))

              (recur (inc i)))))))))

(deftest test-dtls-handshake
  (testing "Full DTLS handshake between client and server"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
          client-engine (dtls/create-engine ctx true)
          server-engine (dtls/create-engine ctx false)]

      (is (= :success (run-handshake client-engine server-engine))))))
