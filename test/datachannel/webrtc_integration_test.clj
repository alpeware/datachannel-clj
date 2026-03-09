(ns datachannel.webrtc-integration-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as dc]
            [datachannel.stun :as stun]
            [datachannel.dtls :as dtls]
            [datachannel.nio :as nio]
            [datachannel.sdp :as sdp])
  (:import [dev.onvoid.webrtc PeerConnectionFactory RTCConfiguration PeerConnectionObserver RTCIceServer RTCIceCandidate RTCSessionDescription RTCSdpType RTCDataChannelObserver]
           [dev.onvoid.webrtc.media.audio HeadlessAudioDeviceModule]
           [java.util ArrayList]
           [java.net InetAddress InetSocketAddress]
           [java.nio ByteBuffer]))

(defn get-local-ip []
  (.getHostAddress (InetAddress/getLocalHost)))

(defn extract-ice-credentials [sdp]
  {:ufrag (second (re-find #"a=ice-ufrag:([^\r\n]+)" sdp))
   :pwd (second (re-find #"a=ice-pwd:([^\r\n]+)" sdp))})

(defn create-offer [pc]
  (let [p (promise)]
    (.createOffer pc (dev.onvoid.webrtc.RTCOfferOptions.)
                  (reify dev.onvoid.webrtc.CreateSessionDescriptionObserver
                    (onSuccess [_ description] (deliver p description))
                    (onFailure [_ error] (deliver p (ex-info "Create offer failed" {:error error})))))
    @p))

(defn set-local-description [pc description]
  (let [p (promise)]
    (.setLocalDescription pc description
                          (reify dev.onvoid.webrtc.SetSessionDescriptionObserver
                            (onSuccess [_] (deliver p true))
                            (onFailure [_ error] (deliver p (ex-info "Set local description failed" {:error error})))))
    @p))

(defn set-remote-description [pc description]
  (let [p (promise)]
    (.setRemoteDescription pc description
                           (reify dev.onvoid.webrtc.SetSessionDescriptionObserver
                             (onSuccess [_] (deliver p true))
                             (onFailure [_ error] (deliver p (ex-info "Set remote description failed" {:error error})))))
    @p))

(deftest test-webrtc-integration
  (println "Starting full WebRTC integration test...")
  (let [adm (HeadlessAudioDeviceModule.)
        factory (PeerConnectionFactory. adm)
        config (RTCConfiguration.)
        ice-servers (ArrayList.)]
    (let [ice-server (RTCIceServer.)]
      (set! (.urls ice-server) (doto (ArrayList.) (.add "stun:stun.l.google.com:19302")))
      (.add ice-servers ice-server))
    (set! (.iceServers config) ice-servers)

    (let [stun-bound (promise)
          dtls-handshake-done (promise)
          sctp-connected (promise)
          msg-received (promise)
          remote-creds (atom nil)
          local-ip (get-local-ip)
          port (+ 35000 (rand-int 5000))
          ice-creds (sdp/generate-ice-credentials)
          ice-ufrag (:ufrag ice-creds)
          ice-pwd (:pwd ice-creds)

          channel (nio/create-non-blocking-channel port local-ip)
          selector (nio/create-selector)
          _ (nio/register-for-read channel selector)
          clj-conn (dc/create-connection {:ice-ufrag ice-ufrag
                                          :ice-pwd ice-pwd
                                          :mtu 1200} false)
          cert-data (:cert-data clj-conn)
          server-cert-fingerprint (:fingerprint cert-data)
          engine (:dtls/engine clj-conn)
          state-atom (atom clj-conn)
          running (atom true)]

      (try
        ;; Start NIO processing loop
        (let [java-peer-addr (atom nil)]
          (future
            (let [buffer (ByteBuffer/allocateDirect 65536)]
              (try
                (while @running
                  (when (> (.select selector 10) 0)
                    (let [keys (.selectedKeys selector)
                          iter (.iterator keys)]
                      (while (.hasNext iter)
                        (let [k (.next iter)]
                          (.remove iter)
                          (when (.isReadable k)
                            (.clear buffer)
                            (when-let [remote-addr (.receive channel buffer)]
                              (.flip buffer)
                              (when-not @java-peer-addr
                                (reset! java-peer-addr remote-addr))
                              (let [len (.remaining buffer)
                                    bytes (byte-array len)]
                                (.get buffer bytes)
                                ;; Let dc/handle-receive do STUN and DTLS decryption/handshake
                                (let [state @state-atom
                                      result (dc/handle-receive state bytes (System/currentTimeMillis) remote-addr)]
                                  (reset! state-atom (:new-state result))

                                  ;; Emit outbound bytes
                                  (let [serialized (dc/serialize-network-out result)]
                                    (doseq [out-buf (:network-out-bytes serialized)]
                                      (.send channel out-buf @java-peer-addr)))

                                ;; Parse events and deliver promises
                                (doseq [ev (:app-events result)]
                                  (cond
                                    (= (:type ev) :stun-packet)
                                    (let [parsed (stun/parse-packet (ByteBuffer/wrap (:payload ev)))]
                                      (println "Got STUN: " (:type parsed))
                                      (when (or (= (:type parsed) 0x0001) (= (:type parsed) 0x0101))
                                        (when-not (realized? stun-bound)
                                          (deliver stun-bound true))))

                                    (or (= (:type ev) :dtls-packet) (= (:type ev) :dtls-handshake-progress))
                                    (let [hs-status (.getHandshakeStatus engine)]
                                      (println "Got DTLS. Status: " hs-status)
                                      (when (or (= hs-status javax.net.ssl.SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                                                (= hs-status javax.net.ssl.SSLEngineResult$HandshakeStatus/FINISHED))
                                        (when (realized? stun-bound)
                                          (when-not (realized? dtls-handshake-done)
                                            (deliver dtls-handshake-done true)))))

                                    (= (:type ev) :on-state-change)
                                    (when (= (:state ev) :established)
                                      (when-not (realized? sctp-connected)
                                        (deliver sctp-connected true)))

                                    (= (:type ev) :on-message)
                                    (deliver msg-received (String. ^bytes (:payload ev) "UTF-8")))))))))))))
              (catch Exception e
                (println "Error in UDP loop:" e)
                (.printStackTrace e))))))

        ;; Setup Observer and PC
        (let [java-dc (atom nil)
              observer (reify PeerConnectionObserver
                         (onIceCandidate [_ candidate]
                           (when-let [creds @remote-creds]
                             (try
                               (let [cand-info (sdp/parse-candidate (.sdp candidate))
                                     req (stun/make-binding-request ice-ufrag (:ufrag creds) (:pwd creds))
                                     addr (InetSocketAddress. ^String (:ip cand-info) (int (:port cand-info)))]
                                 (.send channel req addr))
                               (catch Exception e))))
                         (onIceConnectionChange [_ state]
                           (println "Java ICE State:" state))
                         (onConnectionChange [_ state]
                           (println "Java Connection State:" state))
                         (onSignalingChange [_ state])
                         (onIceGatheringChange [_ state])
                         (onIceCandidatesRemoved [_ candidates])
                         (onAddStream [_ stream])
                         (onRemoveStream [_ stream])
                         (onDataChannel [_ ch]
                           (reset! java-dc ch))
                         (onRenegotiationNeeded [_])
                         (onAddTrack [_ receiver streams])
                         (onTrack [_ transceiver])
                         (onIceCandidateError [_ event]))
              pc (.createPeerConnection factory config observer)
              dc-init (dev.onvoid.webrtc.RTCDataChannelInit.)]
          (set! (.ordered dc-init) true)
          (set! (.negotiated dc-init) false)

          (let [ch (.createDataChannel pc "test" dc-init)]
            (.registerObserver ch (reify dev.onvoid.webrtc.RTCDataChannelObserver
                                    (onStateChange [_]
                                      (when (= dev.onvoid.webrtc.RTCDataChannelState/OPEN (.getState ch))
                                        (deliver sctp-connected true)))
                                    (onMessage [_ buffer]
                                      (let [bytes (byte-array (.remaining buffer))]
                                        (.get buffer bytes)
                                        (deliver msg-received (String. bytes "UTF-8"))))
                                    (onBufferedAmountChange [_ amount])))
            (reset! java-dc ch))

          (let [offer (create-offer pc)]
            (reset! remote-creds (extract-ice-credentials (.sdp offer)))
            (set-local-description pc offer)

            (let [sdp-str (sdp/format-answer
                           {:local-ip    local-ip
                            :port        port
                            :ice-ufrag   ice-ufrag
                            :ice-pwd     ice-pwd
                            :fingerprint server-cert-fingerprint})
                  answer (RTCSessionDescription. RTCSdpType/ANSWER sdp-str)]

              (set-remote-description pc answer)

              (let [candidate-str (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")]
                (.addIceCandidate pc (RTCIceCandidate. "0" 0 candidate-str))))

            (println "Waiting for STUN binding...")
            (loop [i 0]
              (if (or (realized? stun-bound) (> i 50))
                nil
                (do
                  ;; actively knock java side to unlock its STUN/ICE state
                  (when-let [creds @remote-creds]
                    (try
                      (let [req (stun/make-binding-request ice-ufrag (:ufrag creds) (:pwd creds))
                            remote-cand (first (.getIceCandidates pc))
                            addr (if remote-cand
                                   (let [cand-info (sdp/parse-candidate (.sdp remote-cand))]
                                     (InetSocketAddress. ^String (:ip cand-info) (int (:port cand-info))))
                                   (InetSocketAddress. ^String local-ip (int port)))]
                        (.send channel req addr))
                      (catch Exception e)))
                  (Thread/sleep 100)
                  (recur (inc i)))))
            (let [v (deref stun-bound 100 :timeout)]
              (is (= v true) "STUN binding should complete"))

            (println "Waiting for DTLS handshake...")
            (loop [i 0]
              (if (or (realized? dtls-handshake-done) (> i 100))
                nil
                (do (Thread/sleep 100) (recur (inc i)))))
            (let [v (deref dtls-handshake-done 10000 :timeout)]
              (is (= v true) "DTLS handshake should complete"))

            (println "Waiting for SCTP connected...")
            (let [v (deref sctp-connected 5000 :timeout)]
              (is (= v true) "SCTP should connect"))

            (println "Sending DataChannel Message from Java -> Clojure")
            (let [msg-bytes (.getBytes "Hello from Java!" "UTF-8")
                  buf (dev.onvoid.webrtc.RTCDataChannelBuffer. (ByteBuffer/wrap msg-bytes) false)]
              (.send @java-dc buf))

            (println "Waiting for Message at Clojure...")
            (let [v (deref msg-received 5000 :timeout)]
              (is (= v "Hello from Java!") "Message should match"))))
        (finally
          (reset! running false)
          (try (.close channel) (catch Exception _))
          (try (.close selector) (catch Exception _))
          (try (.dispose factory) (catch Exception _))
          (try (.dispose adm) (catch Exception _)))))))
