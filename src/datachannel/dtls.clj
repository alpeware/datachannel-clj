(ns datachannel.dtls
  (:require [clojure.string :as string]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io])
  (:import
   [java.nio ByteBuffer]
   [java.security KeyStore SecureRandom MessageDigest]
   [javax.net.ssl SSLContext SSLEngine SSLEngineResult SSLEngineResult$HandshakeStatus SSLEngineResult$Status KeyManagerFactory TrustManagerFactory X509TrustManager X509ExtendedKeyManager]
   [java.security.cert X509Certificate]
   [java.io File FileInputStream]))

(defn fingerprint [cert]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.getEncoded cert)
         (.update md))
    (->> (.digest md)
         (map #(format "%02X" (bit-and % 0xff)))
         (string/join ":"))))

(defn generate-cert []
  (let [filename (str "temp-keystore-" (java.util.UUID/randomUUID) ".p12")
        password "password"
        alias "webrtc"]
    (try
      (let [result (sh "keytool" "-genkeypair"
                       "-alias" alias
                       "-keyalg" "RSA"
                       "-keysize" "2048"
                       "-storetype" "PKCS12"
                       "-keystore" filename
                       "-storepass" password
                       "-keypass" password
                       "-dname" "CN=WebRTC, O=Clojure, C=US"
                       "-validity" "3650")]
        (if (not= 0 (:exit result))
          (do
            (println "Keytool error:" (:err result))
            nil)
          (let [ks (KeyStore/getInstance "PKCS12")]
            (with-open [fis (FileInputStream. filename)]
              (.load ks fis (.toCharArray password)))
            (let [key (.getKey ks alias (.toCharArray password))
                  cert (.getCertificate ks alias)]
              {:cert cert
               :key key
               :fingerprint (fingerprint cert)}))))
      (finally
        (io/delete-file filename true)))))

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

;; Packet handling

(defn- handle-handshake [^SSLEngine engine ^ByteBuffer net-in ^ByteBuffer net-out app-in ^ByteBuffer app-out]
  (let [status (.getHandshakeStatus engine)]
    (condp = status
      SSLEngineResult$HandshakeStatus/NEED_TASK
      (do
        (.run (.getDelegatedTask engine))
        :need-task)

      SSLEngineResult$HandshakeStatus/NEED_WRAP
      (let [res (.wrap engine app-in net-out)]
        #_(println "WRAP" (.getStatus res) (.getHandshakeStatus res))
        :wrapped)

      SSLEngineResult$HandshakeStatus/NEED_UNWRAP
      (let [res (.unwrap engine net-in app-out)]
        #_(println "UNWRAP" (.getStatus res) (.getHandshakeStatus res))
        (if (= (.getStatus res) SSLEngineResult$Status/BUFFER_UNDERFLOW)
           :underflow
           :unwrapped))

      SSLEngineResult$HandshakeStatus/FINISHED
      :finished

      SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING
      :not-handshaking

      status)))

;; This is a high-level wrapper.
;; `engine`: SSLEngine
;; `net-in`: ByteBuffer containing incoming UDP packet
;; `net-out`: ByteBuffer to write outgoing UDP packet
;; `app-in`: ByteBuffer containing outgoing Application data (SCTP)
;; `app-out`: ByteBuffer to write incoming Application data (SCTP)
(defn step [^SSLEngine engine ^ByteBuffer net-in ^ByteBuffer net-out ^ByteBuffer app-in ^ByteBuffer app-out]
  (handle-handshake engine net-in net-out app-in app-out))
