(ns datachannel.dtls
  (:require [clojure.string :as string]
            [clojure.java.io :as io])
  (:import
   [java.nio ByteBuffer]
   [java.security KeyStore SecureRandom MessageDigest PrivateKey]
   [javax.net.ssl SSLContext SSLEngine SSLEngineResult SSLEngineResult$HandshakeStatus SSLEngineResult$Status KeyManagerFactory TrustManagerFactory X509TrustManager X509ExtendedKeyManager]
   [java.security.cert X509Certificate]
   [java.io File FileInputStream]
   [sun.security.tools.keytool CertAndKeyGen]
   [sun.security.x509 X500Name]
   [java.util Date]))

(defn fingerprint [cert]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.getEncoded cert)
         (.update md))
    (->> (.digest md)
         (map #(format "%02X" (bit-and % 0xff)))
         (string/join ":"))))

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

(defn create-ssl-context [cert key]
  (let [ks (KeyStore/getInstance "PKCS12")
        kmf (KeyManagerFactory/getInstance "SunX509")
        tmf (TrustManagerFactory/getInstance "SunX509")
        ctx (SSLContext/getInstance "DTLS")]
    (.load ks nil nil)
    (.setKeyEntry ks "webrtc" key (char-array "password") (into-array X509Certificate [cert]))
    (.init kmf ks (char-array "password"))
    (.init tmf ks)

    ;; Create a trust manager that accepts the peer's certificate (for WebRTC DTLS-SRTP, we verify via fingerprint in SDP)
    (let [tm (reify X509TrustManager
               (checkClientTrusted [_ chain auth-type])
               (checkServerTrusted [_ chain auth-type])
               (getAcceptedIssuers [_] (make-array X509Certificate 0)))]
      (.init ctx (.getKeyManagers kmf) (into-array [tm]) nil))
    ctx))

(defn create-engine [^SSLContext context client-mode]
  (let [engine (.createSSLEngine context)]
    (.setUseClientMode engine client-mode)
    (.setNeedClientAuth engine true) ;; WebRTC requires mutual auth
    engine))

(def buffer-size 65536)

(defn- make-buffer []
  (ByteBuffer/allocateDirect buffer-size))

(defn- buffer->bytes [^ByteBuffer buf]
  (let [len (.remaining buf)
        bs (byte-array len)]
    (.get buf bs)
    bs))

(defn handshake
  "Runs the DTLS handshake, driving the SSLEngine until it's finished,
  needs more data from the peer, or produces data for the peer.
  `engine`: The SSLEngine.
  `in`: A ByteBuffer containing incoming handshake data from the peer. Can be empty.
  `out`: A ByteBuffer to write outgoing handshake data to.
  Returns a map with:
  :status - The SSLEngineResult$HandshakeStatus.
  :bytes - A byte array of outgoing data to be sent to the peer, if any."
  [^SSLEngine engine ^ByteBuffer in ^ByteBuffer out]
  (.clear out)
  (loop [status (.getHandshakeStatus engine)]
    (condp = status
      (SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING SSLEngineResult$HandshakeStatus/FINISHED)
      {:status status}

      SSLEngineResult$HandshakeStatus/NEED_TASK
      (do
        (when-let [task (.getDelegatedTask engine)]
          (.run task))
        (recur (.getHandshakeStatus engine)))

      SSLEngineResult$HandshakeStatus/NEED_WRAP
      (let [res (.wrap engine (ByteBuffer/allocate 0) out)]
        (.flip out)
        {:status (.getHandshakeStatus res)
         :bytes (buffer->bytes out)})

      (SSLEngineResult$HandshakeStatus/NEED_UNWRAP SSLEngineResult$HandshakeStatus/NEED_UNWRAP_AGAIN)
      (if (.hasRemaining in)
        (let [res (.unwrap engine in (make-buffer))]
          (if (= (.getStatus res) SSLEngineResult$Status/BUFFER_UNDERFLOW)
            {:status status}
            (recur (.getHandshakeStatus res))))
        {:status status}))))

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
  (let [res (.unwrap engine net-in app-out)]
    (.flip app-out)
    {:status (.getStatus res)
     :bytes (buffer->bytes app-out)}))
