(ns datachannel.dtls-test
  (:require [clojure.test :refer :all]
            [datachannel.dtls :as dtls])
  (:import [javax.net.ssl SSLContext SSLEngine SSLEngineResult$HandshakeStatus SSLEngineResult$Status]
           [java.nio ByteBuffer]))

(deftest test-fingerprint
  (testing "Fingerprint generation"
    (let [cert-data (dtls/generate-cert)]
      (is (map? cert-data))
      (is (some? (:cert cert-data)))
      (is (some? (:key cert-data)))
      (is (string? (:fingerprint cert-data)))
      (is (re-matches #"[0-9A-F]{2}(:[0-9A-F]{2})+" (:fingerprint cert-data))))))

(deftest e2e-handshake
  (testing "Full DTLS handshake and data transfer"
    (let [server-cert-data (dtls/generate-cert)
          client-cert-data (dtls/generate-cert)

          server-ctx (dtls/create-ssl-context (:cert server-cert-data) (:key server-cert-data))
          client-ctx (dtls/create-ssl-context (:cert client-cert-data) (:key client-cert-data))

          server-engine (dtls/create-engine server-ctx false)
          client-engine (dtls/create-engine client-ctx true)

          c-to-s (ByteBuffer/allocateDirect dtls/buffer-size)
          s-to-c (ByteBuffer/allocateDirect dtls/buffer-size)]
      (.beginHandshake client-engine)
      (loop [i 0]
        (when (> i 15) (throw (Exception. "Handshake failed to complete")))
        (let [client-hs-status (.getHandshakeStatus client-engine)
              server-hs-status (.getHandshakeStatus server-engine)]
          (when-not (and (= client-hs-status SSLEngineResult$HandshakeStatus/FINISHED)
                         (= server-hs-status SSLEngineResult$HandshakeStatus/FINISHED))
            (.flip s-to-c)
            (.flip c-to-s)
            (let [client-res (dtls/handshake client-engine s-to-c c-to-s)
                  server-res (dtls/handshake server-engine c-to-s s-to-c)]
              (.compact s-to-c)
              (.compact c-to-s)
              (recur (inc i))))))
      (is (= SSLEngineResult$HandshakeStatus/FINISHED (.getHandshakeStatus client-engine)))
      (is (= SSLEngineResult$HandshakeStatus/FINISHED (.getHandshakeStatus server-engine)))

      ;; Data transfer client -> server
      (let [client-app-out (ByteBuffer/allocateDirect dtls/buffer-size)
            server-app-in (ByteBuffer/allocateDirect dtls/buffer-size)
            test-message "Hello from client"

            encrypted-data (dtls/send-app-data client-engine (ByteBuffer/wrap (.getBytes test-message)) client-app-out)
            decrypted-data (dtls/receive-app-data server-engine (ByteBuffer/wrap (:bytes encrypted-data)) server-app-in)]
        (is (= test-message (String. (:bytes decrypted-data)))))

      ;; Data transfer server -> client
      (let [server-app-out (ByteBuffer/allocateDirect dtls/buffer-size)
            client-app-in (ByteBuffer/allocateDirect dtls/buffer-size)
            test-message "Hello from server"

            encrypted-data (dtls/send-app-data server-engine (ByteBuffer/wrap (.getBytes test-message)) server-app-out)
            decrypted-data (dtls/receive-app-data client-engine (ByteBuffer/wrap (:bytes encrypted-data)) client-app-in)]
        (is (= test-message (String. (:bytes decrypted-data))))))))
