(ns datachannel.dtls
  (:require
   [clojure.string :as str])
  (:import
   [java.nio ByteBuffer]
   [java.security KeyStore MessageDigest]
   [javax.net.ssl SSLContext SSLEngine SSLEngineResult$HandshakeStatus SSLEngineResult$Status KeyManagerFactory TrustManagerFactory X509TrustManager]
   [java.security.cert X509Certificate]
   [java.io ByteArrayOutputStream]
   [sun.security.tools.keytool CertAndKeyGen]
   [sun.security.x509 X500Name]
   [java.util Date ArrayList]))

(def DEFAULT-PACKET-SIZE 16384)

(defn fingerprint [cert]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.getEncoded cert)
         (.update md))
    (-> (java.util.HexFormat/ofDelimiter ":")
        (.withUpperCase)
        (.formatHex (.digest md)))))

(defn generate-cert []
  (let [key-pair-generator (CertAndKeyGen. "RSA" "SHA256WithRSA" nil)
        x500-name (X500Name. "CN=WebRTC, O=Clojure, C=US")]
    (.generate key-pair-generator 2048)
    (let [key (.getPrivateKey key-pair-generator)
          cert (.getSelfCertificate key-pair-generator
                                    x500-name
                                    (Date.)
                                    (* 60 60 24 3650))]
      {:cert cert
       :key key
       :fingerprint (fingerprint cert)})))

(defn verify-peer-fingerprint [^SSLEngine engine expected-fingerprint]
  (try
    (let [cert (->> engine
                    (.getSession)
                    (.getPeerCertificates)
                    (seq)
                    (first))
          actual-fingerprint (fingerprint cert)]
      (= (str/lower-case actual-fingerprint)
         (str/lower-case expected-fingerprint)))
    (catch Exception _
      false)))

(defn create-ssl-context [cert key]
  (let [ks (KeyStore/getInstance "PKCS12")
        kmf (KeyManagerFactory/getInstance "SunX509")
        tmf (TrustManagerFactory/getInstance "SunX509")
        ctx (SSLContext/getInstance "DTLS")
        pwd-chars (char-array (str (java.util.UUID/randomUUID)))]
    (.load ks nil nil)
    (.setKeyEntry ks "webrtc" key pwd-chars (into-array X509Certificate [cert]))
    (.init kmf ks pwd-chars)
    (.init tmf ks)

    ;; Create a trust manager that accepts the peer's certificate (for WebRTC DTLS-SRTP, we verify via fingerprint in SDP)
    (let [tm (reify X509TrustManager
               (checkClientTrusted [_ _chain _auth-type])
               (checkServerTrusted [_ _chain _auth-type])
               (getAcceptedIssuers [_] (make-array X509Certificate 0)))]
      (.init ctx (.getKeyManagers kmf) (into-array [tm]) nil))
    ctx))

(defn create-engine [^SSLContext context client-mode]
  (let [engine (.createSSLEngine context)]
    (.setUseClientMode engine client-mode)
    (let [params (.getSSLParameters engine)]
      (.setNeedClientAuth params true) ;; WebRTC requires mutual auth
      (.setMaximumPacketSize params DEFAULT-PACKET-SIZE)
      (.setSSLParameters engine params)
      engine)))

(def buffer-size 65536)

(defn- make-buffer []
  (ByteBuffer/allocateDirect buffer-size))

(defn- buffer->bytes [^ByteBuffer buf]
  (let [len (.remaining buf)
        bs (byte-array len)]
    (.get buf bs)
    bs))

(defn handshake
  "Runs the DTLS handshake loop until I/O is required.
  `engine`: The SSLEngine.
  `in`: A ByteBuffer containing incoming handshake data from the peer. Can be empty.
  `out`: A ByteBuffer to use as scratch space for outgoing data.

  Returns a map with:
  :status - The SSLEngineResult$HandshakeStatus.
  :packets - A vector of byte arrays (outgoing packets).
  :app-data - A byte array of decrypted application data (if any)."
  [^SSLEngine engine ^ByteBuffer in ^ByteBuffer out]
  (.clear out)
  (let [empty-app-buffer (ByteBuffer/allocate 0)
        packets (ArrayList.)
        app-data-out (ByteArrayOutputStream.)]
    (loop [loops 0]
      (if (> loops 100)
        (throw (Exception. "Too many handshake loops"))
        (let [hs-status (.getHandshakeStatus engine)]
          (condp = hs-status
            SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING
            (if (.hasRemaining in)
              (let [temp-app (make-buffer)
                    res (.unwrap engine in temp-app)]
                (.flip temp-app)
                (when (> (.remaining temp-app) 0)
                  (.write app-data-out (buffer->bytes temp-app) 0 (.remaining temp-app)))
                (condp = (.getStatus res)
                  SSLEngineResult$Status/BUFFER_UNDERFLOW
                  {:status (.getHandshakeStatus engine)
                   :packets (vec packets)
                   :app-data (.toByteArray app-data-out)}
                  SSLEngineResult$Status/CLOSED
                  (throw (Exception. "SSLEngine closed during handshake"))
                  (recur (inc loops))))
              {:status hs-status
               :packets (vec packets)
               :app-data (.toByteArray app-data-out)})

            SSLEngineResult$HandshakeStatus/FINISHED
            {:status hs-status
             :packets (vec packets)
             :app-data (.toByteArray app-data-out)}

            SSLEngineResult$HandshakeStatus/NEED_TASK
            (do
              (while (let [task (.getDelegatedTask engine)]
                       (when task
                         (.run task)
                         task)))
              (recur (inc loops)))

            SSLEngineResult$HandshakeStatus/NEED_WRAP
            (do
              (.clear out)
              (let [res (.wrap engine empty-app-buffer out)]
                (.flip out)
                (when (> (.remaining out) 0)
                  (.add packets (buffer->bytes out)))
                (condp = (.getStatus res)
                  SSLEngineResult$Status/BUFFER_OVERFLOW
                  (throw (Exception. "Buffer overflow during handshake wrap"))
                  SSLEngineResult$Status/CLOSED
                  (throw (Exception. "SSLEngine closed during handshake"))
                  (recur (inc loops)))))

            SSLEngineResult$HandshakeStatus/NEED_UNWRAP
            (if (.hasRemaining in)
              (let [temp-app (make-buffer)
                    res (.unwrap engine in temp-app)]
                (.flip temp-app)
                (when (> (.remaining temp-app) 0)
                  (.write app-data-out (buffer->bytes temp-app) 0 (.remaining temp-app)))
                (condp = (.getStatus res)
                  SSLEngineResult$Status/BUFFER_UNDERFLOW
                  {:status (.getHandshakeStatus engine)
                   :packets (vec packets)
                   :app-data (.toByteArray app-data-out)}
                  SSLEngineResult$Status/CLOSED
                  (throw (Exception. "SSLEngine closed during handshake"))
                  (recur (inc loops))))
              {:status hs-status
               :packets (vec packets)
               :app-data (.toByteArray app-data-out)})

            SSLEngineResult$HandshakeStatus/NEED_UNWRAP_AGAIN
            (let [temp-app (make-buffer)
                  res (.unwrap engine in temp-app)]
              (.flip temp-app)
              (when (> (.remaining temp-app) 0)
                (.write app-data-out (buffer->bytes temp-app) 0 (.remaining temp-app)))
              (condp = (.getStatus res)
                SSLEngineResult$Status/BUFFER_UNDERFLOW
                {:status (.getHandshakeStatus engine)
                 :packets (vec packets)
                 :app-data (.toByteArray app-data-out)}
                SSLEngineResult$Status/CLOSED
                (throw (Exception. "SSLEngine closed during handshake"))
                (recur (inc loops))))))))))

(defn send-app-data
  "Encrypts and sends application data. Should only be called after handshake is complete.
  `engine`: The SSLEngine.
  `app-data`: A ByteBuffer with the application data to send.
  `net-out`: A ByteBuffer to write the encrypted data to.

  Returns a map with:
  :status - The SSLEngineResult$Status.
  :bytes - A byte array of the encrypted data to be sent."
  [^SSLEngine engine ^ByteBuffer app-data ^ByteBuffer net-out]
  (.clear net-out)
  (let [res (.wrap engine app-data net-out)]
    (.flip net-out)
    {:status (.getStatus res)
     :bytes (buffer->bytes net-out)}))

(defn receive-app-data
  "Receives and decrypts application data.
  `engine`: The SSLEngine.
  `net-in`: A ByteBuffer containing encrypted data from the peer.
  `app-out`: A ByteBuffer to write the decrypted application data to.

  Returns a map with:
  :status - The SSLEngineResult$Status.
  :bytes - A byte array of the decrypted application data, if any."
  [^SSLEngine engine ^ByteBuffer net-in ^ByteBuffer app-out]
  (.clear app-out)
  (try
    (let [res (.unwrap engine net-in app-out)]
      (.flip app-out)
      {:status (.getStatus res)
       :bytes (buffer->bytes app-out)})
    (catch Exception _
      {:status nil :bytes nil})))
