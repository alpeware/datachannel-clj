(ns datachannel.dtls
  (:require [clojure.string :as string]
            [clojure.java.io :as io])
  (:import
   [java.nio ByteBuffer]
   [java.security KeyStore SecureRandom MessageDigest PrivateKey]
   [javax.net.ssl SSLContext SSLEngine SSLEngineResult SSLEngineResult$HandshakeStatus SSLEngineResult$Status KeyManagerFactory TrustManagerFactory X509TrustManager X509ExtendedKeyManager]
   [java.security.cert X509Certificate]
   [java.io File FileInputStream ByteArrayOutputStream]
   [sun.security.tools.keytool CertAndKeyGen]
   [sun.security.x509 X500Name]
   [java.util Date ArrayList]))

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

    (let [tm (reify X509TrustManager
               (checkClientTrusted [_ chain auth-type])
               (checkServerTrusted [_ chain auth-type])
               (getAcceptedIssuers [_] (make-array X509Certificate 0)))]
      (.init ctx (.getKeyManagers kmf) (into-array [tm]) nil))
    ctx))

(defn create-engine [^SSLContext context client-mode]
  (let [engine (.createSSLEngine context)]
    (.setUseClientMode engine client-mode)
    (.setNeedClientAuth engine true)
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
  "Runs the DTLS handshake loop until it's finished or needs more data from the peer.
  This function is intended to be called repeatedly.

  `engine`: The SSLEngine.
  `in`: A ByteBuffer containing incoming handshake data from the peer. Can be empty.

  Returns a map with:
  :status - The final SSLEngineResult$HandshakeStatus.
  :packets - A vector of byte arrays (outgoing packets to be sent).
  :app-data - A byte array of decrypted application data (if any)."
  [^SSLEngine engine ^ByteBuffer in]
  (let [net-out-bb (make-buffer)
        app-out-bb (make-buffer)
        packets (ArrayList.)
        app-data-out (ByteArrayOutputStream.)]
    (loop [loops 0]
      (if (> loops 20)
        (throw (Exception. "Too many handshake loops in a single call"))
        (let [hs-status (.getHandshakeStatus engine)]
          (cond
            (= hs-status SSLEngineResult$HandshakeStatus/FINISHED)
            (recur (inc loops))

            (#{SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING} hs-status)
            {:status hs-status, :packets (vec packets), :app-data (.toByteArray app-data-out)}

            (= hs-status SSLEngineResult$HandshakeStatus/NEED_TASK)
            (do
              (doseq [task (repeatedly #(.getDelegatedTask engine))]
                (when task (.run task)))
              (recur (inc loops)))

            (= hs-status SSLEngineResult$HandshakeStatus/NEED_WRAP)
            (do
              (.clear net-out-bb)
              (let [res (.wrap engine (ByteBuffer/allocate 0) net-out-bb)]
                (when (not= (.getStatus res) SSLEngineResult$Status/OK)
                  (throw (Exception. (str "Wrap failed: " (.getStatus res)))))
                (.flip net-out-bb)
                (when (.hasRemaining net-out-bb)
                  (.add packets (buffer->bytes net-out-bb)))
                (recur (inc loops))))

            (#{SSLEngineResult$HandshakeStatus/NEED_UNWRAP SSLEngineResult$HandshakeStatus/NEED_UNWRAP_AGAIN} hs-status)
            (if (and in (.hasRemaining in))
              (let [res (.unwrap engine in app-out-bb)]
                (.flip app-out-bb)
                (when (.hasRemaining app-out-bb)
                  (.write app-data-out (buffer->bytes app-out-bb) 0 (.remaining app-out-bb)))
                (condp = (.getStatus res)
                  SSLEngineResult$Status/OK
                  (recur (inc loops))

                  SSLEngineResult$Status/BUFFER_UNDERFLOW
                  {:status (.getHandshakeStatus engine), :packets (vec packets), :app-data (.toByteArray app-data-out)}

                  (throw (Exception. (str "Unwrap failed: " (.getStatus res))))))
              {:status hs-status, :packets (vec packets), :app-data (.toByteArray app-data-out)})))))))

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
