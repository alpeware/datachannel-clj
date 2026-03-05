(ns datachannel.handshake-test
  (:require [clojure.test :refer :all]
            [datachannel.dtls :as dtls])
  (:import [java.nio ByteBuffer]
           [javax.net.ssl SSLEngine SSLEngineResult$HandshakeStatus SSLEngineResult]))

(defn- run-handshake-loop [client-engine server-engine]
  (let [client-out (ByteBuffer/allocate 65536)
        server-out (ByteBuffer/allocate 65536)
        client-in (ByteBuffer/allocate 65536)
        server-in (ByteBuffer/allocate 65536)
        max-loops 100]
    (.flip client-in)
    (.flip server-in)
    (loop [i 0]
      (if (> i max-loops)
        (throw (Exception. "Handshake failed to complete in max loops"))
        (let [client-status (.getHandshakeStatus client-engine)
              server-status (.getHandshakeStatus server-engine)]
          (if (and (or (= client-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                       (= client-status SSLEngineResult$HandshakeStatus/FINISHED))
                   (or (= server-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                       (= server-status SSLEngineResult$HandshakeStatus/FINISHED))
                   (not (.hasRemaining client-in))
                   (not (.hasRemaining server-in)))
            :success
            (do
              ;; Run client step
              (let [res-c (dtls/handshake client-engine client-in client-out)
                    packets-c (:packets res-c)]
                (.compact server-in)
                (doseq [p packets-c]
                  (.put server-in (ByteBuffer/wrap p)))
                (.flip server-in))
              ;; Run server step
              (let [res-s (dtls/handshake server-engine server-in server-out)
                    packets-s (:packets res-s)]
                (.compact client-in)
                (doseq [p packets-s]
                  (.put client-in (ByteBuffer/wrap p)))
                (.flip client-in))
              (recur (inc i)))))))))

(defn- exchange-data [client-engine server-engine msg]
  (let [client-net-out (ByteBuffer/allocate 65536)
        server-app-out (ByteBuffer/allocate 65536)
        client-app-in (ByteBuffer/wrap (.getBytes msg))]
    (let [res-send (dtls/send-app-data client-engine client-app-in client-net-out)
          res-recv (dtls/receive-app-data server-engine (ByteBuffer/wrap (:bytes res-send)) server-app-out)]
      (String. (:bytes res-recv)))))

(deftest test-initial-handshake
  (testing "Initial DTLS handshake between client and server"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
          client-engine (dtls/create-engine ctx true)
          server-engine (dtls/create-engine ctx false)]
      (.beginHandshake client-engine)
      (.beginHandshake server-engine)
      (is (= :success (run-handshake-loop client-engine server-engine)))
      (is (= "Hello World" (exchange-data client-engine server-engine "Hello World"))))))

(deftest test-unsolicited-handshake
  (testing "Handshake where only one side calls beginHandshake explicitly"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
          client-engine (dtls/create-engine ctx true)
          server-engine (dtls/create-engine ctx false)]
      (.beginHandshake client-engine)
      ;; server-engine.beginHandshake() is NOT called
      (is (= :success (run-handshake-loop client-engine server-engine)))
      (is (= "Hello" (exchange-data client-engine server-engine "Hello"))))))

(deftest test-max-packet-size
  (testing "Handshake with maximum packet size constraint"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
          client-engine (dtls/create-engine ctx true)
          server-engine (dtls/create-engine ctx false)
          params (.getSSLParameters client-engine)]
      ;; Set a small max packet size to force fragmentation
      (.setMaximumPacketSize params 512)
      (.setSSLParameters client-engine params)
      (.setSSLParameters server-engine params)

      (.beginHandshake client-engine)
      (.beginHandshake server-engine)
      (is (= :success (run-handshake-loop client-engine server-engine)))
      (is (= "Hello Frag" (exchange-data client-engine server-engine "Hello Frag"))))))

(defn- run-handshake-loop-with-replication [client-engine server-engine]
  (let [client-out (ByteBuffer/allocate 65536)
        server-out (ByteBuffer/allocate 65536)
        client-in (ByteBuffer/allocate 65536)
        server-in (ByteBuffer/allocate 65536)
        max-loops 200]
    (.flip client-in)
    (.flip server-in)
    (loop [i 0]
      (if (> i max-loops)
        (throw (Exception. "Handshake failed to complete in max loops"))
        (let [client-status (.getHandshakeStatus client-engine)
              server-status (.getHandshakeStatus server-engine)]
          (if (and (or (= client-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                       (= client-status SSLEngineResult$HandshakeStatus/FINISHED))
                   (or (= server-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                       (= server-status SSLEngineResult$HandshakeStatus/FINISHED))
                   (not (.hasRemaining client-in))
                   (not (.hasRemaining server-in)))
            :success
            (do
              ;; Run client step
              (let [res-c (dtls/handshake client-engine client-in client-out)
                    packets-c (:packets res-c)]
                (.compact server-in)
                (doseq [p packets-c]
                  ;; Replicate packet
                  (.put server-in (ByteBuffer/wrap p))
                  (.put server-in (ByteBuffer/wrap p)))
                (.flip server-in))
              ;; Run server step
              (let [res-s (dtls/handshake server-engine server-in server-out)
                    packets-s (:packets res-s)]
                (.compact client-in)
                (doseq [p packets-s]
                  ;; Replicate packet
                  (.put client-in (ByteBuffer/wrap p))
                  (.put client-in (ByteBuffer/wrap p)))
                (.flip client-in))
              (recur (inc i)))))))))

(deftest test-handshake-with-replicated-packets
  (testing "DTLS handshake robustness against replicated packets"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
          client-engine (dtls/create-engine ctx true)
          server-engine (dtls/create-engine ctx false)]
      (.beginHandshake client-engine)
      (.beginHandshake server-engine)
      (is (= :success (run-handshake-loop-with-replication client-engine server-engine)))
      (is (= "Hello Dup" (exchange-data client-engine server-engine "Hello Dup"))))))


(deftest test-client-auth
  (testing "DTLS client authentication requirement"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
          client-engine (dtls/create-engine ctx true)
          server-engine (dtls/create-engine ctx false)]
      ;; Verify server is configured for mutual auth by default in create-engine
      (is (.getNeedClientAuth (.getSSLParameters server-engine)))
      (.beginHandshake client-engine)
      (.beginHandshake server-engine)
      ;; Handshake should succeed as both have certificates (mutual auth)
      (is (= :success (run-handshake-loop client-engine server-engine)))
      (is (= "Auth OK" (exchange-data client-engine server-engine "Auth OK")))
      ;; Verify peer certificate was received
      (is (some? (.getPeerCertificates (.getSession server-engine))))
      (is (some? (.getPeerCertificates (.getSession client-engine)))))))

(deftest test-default-max-packet-size
  (testing "Verify default max packet size in created engine"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
          engine (dtls/create-engine ctx true)]
      (is (= dtls/DEFAULT-PACKET-SIZE (.getMaximumPacketSize (.getSSLParameters engine)))))))

(deftest test-sequence-number
  (testing "DTLS Sequence Number support in application data exchange"
    (let [cert-data (dtls/generate-cert)
          ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
          client-engine (dtls/create-engine ctx true)
          server-engine (dtls/create-engine ctx false)]
      (.beginHandshake client-engine)
      (.beginHandshake server-engine)
      (is (= :success (run-handshake-loop client-engine server-engine)))

      (let [big-message "Very very big message. One two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty."
            big-message-bytes (.getBytes big-message)
            pieces-number 15
            symbols-in-a-message (quot (alength big-message-bytes) pieces-number)
            symbols-in-the-last-message (+ symbols-in-a-message (rem (alength big-message-bytes) pieces-number))

            ;; Create pieces
            sent-messages
            (vec (for [i (range pieces-number)]
                   (if (= i (dec pieces-number))
                     (ByteBuffer/wrap big-message-bytes (* i symbols-in-a-message) symbols-in-the-last-message)
                     (ByteBuffer/wrap big-message-bytes (* i symbols-in-a-message) symbols-in-a-message))))

            ;; Wrap messages in direct order
            wrapped-results
            (loop [i 0
                   prev-seq -1
                   results []]
              (if (< i pieces-number)
                (let [in-buf (sent-messages i)
                      out-buf (ByteBuffer/allocate (.getPacketBufferSize (.getSession client-engine)))
                      result (.wrap client-engine in-buf out-buf)
                      seq-num (.sequenceNumber result)]
                  (is (> seq-num prev-seq) "Sequence number should be monotonically increasing")
                  (.flip out-buf)
                  (let [arr (byte-array (.remaining out-buf))]
                    (.get out-buf arr)
                    (recur (inc i) seq-num (conj results {:seq-num seq-num :bytes arr}))))
                results))

            ;; Unwrap messages in random order
            receiving-sequence (shuffle (range pieces-number))
            recv-map
            (loop [i 0
                   m (sorted-map)]
              (if (< i pieces-number)
                (let [recv-now (nth receiving-sequence i)
                      wrapped-bytes (:bytes (wrapped-results recv-now))
                      in-buf (ByteBuffer/wrap wrapped-bytes)
                      out-buf (ByteBuffer/allocate (.getApplicationBufferSize (.getSession server-engine)))
                      result (.unwrap server-engine in-buf out-buf)
                      seq-num (.sequenceNumber result)]
                  (.flip out-buf)
                  (let [arr (byte-array (.remaining out-buf))]
                    (.get out-buf arr)
                    (recur (inc i) (assoc m seq-num arr))))
                m))]

        ;; Reconstruct and verify
        (is (= pieces-number (count recv-map)))

        (let [reconstructed-bytes
              (let [total-len (reduce + (map alength (vals recv-map)))
                    res (byte-array total-len)]
                (loop [chunks (vals recv-map)
                       offset 0]
                  (when-let [chunk (first chunks)]
                    (System/arraycopy chunk 0 res offset (alength chunk))
                    (recur (rest chunks) (+ offset (alength chunk)))))
                res)
              reconstructed-msg (String. reconstructed-bytes)]
          (is (= big-message reconstructed-msg)))))))
