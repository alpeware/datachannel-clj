(ns datachannel.handshake-test
  (:require [clojure.test :refer :all]
            [datachannel.dtls :as dtls])
  (:import [java.nio ByteBuffer]))

(deftest handshake-test
  (testing "Full DTLS handshake and data exchange"
    (let [{:keys [cert key]} (dtls/generate-cert)
          ssl-context (dtls/create-ssl-context cert key)
          client-engine (dtls/create-engine ssl-context true)
          server-engine (dtls/create-engine ssl-context false)]

      (.beginHandshake client-engine)

      (loop [client-to-server-packets (list (byte-array 0))
             server-to-client-packets '()
             loops 0]
        (if (> loops 20)
          (throw (Exception. "Too many handshake loops"))
          (let [client-in-bb (ByteBuffer/wrap (byte-array (apply concat server-to-client-packets)))
                server-in-bb (ByteBuffer/wrap (byte-array (apply concat client-to-server-packets)))

                client-hs-res (dtls/handshake client-engine client-in-bb)
                server-hs-res (dtls/handshake server-engine server-in-bb)

                client-finished? (= (:status client-hs-res) javax.net.ssl.SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                server-finished? (= (:status server-hs-res) javax.net.ssl.SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)]

            (if (and client-finished? server-finished?)
              :finished
              (recur
               (:packets client-hs-res)
               (:packets server-hs-res)
               (inc loops))))))

      (is (= javax.net.ssl.SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING
             (.getHandshakeStatus client-engine)))
      (is (= javax.net.ssl.SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING
             (.getHandshakeStatus server-engine)))

      (let [client-out-bb (ByteBuffer/allocate dtls/buffer-size)
            server-out-bb (ByteBuffer/allocate dtls/buffer-size)
            client-app-data (ByteBuffer/wrap (.getBytes "Hello from client"))
            server-app-data (ByteBuffer/wrap (.getBytes "Hello from server"))
            client-encrypted (dtls/send-app-data client-engine client-app-data client-out-bb)
            server-decrypted (dtls/receive-app-data server-engine (ByteBuffer/wrap (:bytes client-encrypted)) server-out-bb)
            server-encrypted (dtls/send-app-data server-engine server-app-data server-out-bb)
            client-decrypted (dtls/receive-app-data client-engine (ByteBuffer/wrap (:bytes server-encrypted)) client-out-bb)]
        (is (= "Hello from client" (String. (:bytes server-decrypted))))
        (is (= "Hello from server" (String. (:bytes client-decrypted))))))))
