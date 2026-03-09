(ns datachannel.stun-webrtc-integration-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as dc]
            [datachannel.stun :as stun]
            [datachannel.dtls :as dtls]
            [datachannel.nio :as nio])
  (:import [dev.onvoid.webrtc PeerConnectionFactory RTCConfiguration PeerConnectionObserver RTCIceServer RTCIceCandidate RTCSessionDescription RTCSdpType]
           [dev.onvoid.webrtc.media.audio HeadlessAudioDeviceModule]
           [java.util ArrayList]
           [java.net InetAddress InetSocketAddress]
           [java.nio ByteBuffer]))

(defn get-local-ip []
  (.getHostAddress (InetAddress/getLocalHost)))

(defn extract-ice-credentials [sdp]
  {:ufrag (second (re-find #"a=ice-ufrag:([^\r\n]+)" sdp))
   :pwd (second (re-find #"a=ice-pwd:([^\r\n]+)" sdp))})

(defn parse-candidate [candidate-sdp]
  (let [parts (.split candidate-sdp " ")]
    {:ip (get parts 4)
     :port (Integer/parseInt (get parts 5))}))

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

(deftest test-stun-integration
  (println "Starting STUN WebRTC integration test...")
  (let [adm (HeadlessAudioDeviceModule.)
        factory (PeerConnectionFactory. adm)
        config (RTCConfiguration.)
        ice-servers (ArrayList.)]
    ;; Use Google STUN server to help initialize ICE agent
    (let [ice-server (RTCIceServer.)]
      (set! (.urls ice-server) (doto (ArrayList.) (.add "stun:stun.l.google.com:19302")))
      (.add ice-servers ice-server))
    (set! (.iceServers config) ice-servers)

    (let [stun-received-at-clj (atom false)
          remote-creds (atom nil)
          local-ip (get-local-ip)
          port (+ 25000 (rand-int 5000))
          ice-ufrag "testufrag"
          ice-pwd "testpwd"]

        (let [channel (nio/create-non-blocking-channel port local-ip)
              selector (nio/create-selector)
              _ (nio/register-for-read channel selector)
              cert-data (dtls/generate-cert)
              server-cert-fingerprint (:fingerprint cert-data)
              running (atom true)
              buffer (ByteBuffer/allocateDirect 65536)
              loop-future
              (future
                (try
                  (while @running
                    (when (> (.select selector 100) 0)
                      (let [keys (.selectedKeys selector)
                            iter (.iterator keys)]
                        (while (.hasNext iter)
                          (let [k (.next iter)]
                            (.remove iter)
                            (when (.isReadable k)
                              (.clear buffer)
                              (when-let [remote-addr (.receive channel buffer)]
                                (.flip buffer)
                                (let [len (.remaining buffer)
                                      bytes (byte-array len)]
                                  (.get buffer bytes)
                                  (let [result (dc/handle-receive {:ice-pwd ice-pwd} bytes (System/currentTimeMillis) remote-addr nil)]
                                    (doseq [event (:app-events result)]
                                      (when (= (:type event) :stun-packet)
                                        (let [parsed (stun/parse-packet (ByteBuffer/wrap (:payload event)))
                                              msg-type (:type parsed)]
                                          (when (or (= msg-type 0x0001) (= msg-type 0x0101))
                                            (reset! stun-received-at-clj true)))))
                                    (let [serialized (dc/serialize-network-out result nil nil)]
                                      (doseq [out-buf (:network-out-bytes serialized)]
                                        (.send channel out-buf remote-addr))))))))))))
                  (catch Exception e
                    (println "Error in UDP loop:" e))))

              observer (reify PeerConnectionObserver
                         (onIceCandidate [_ candidate]
                           (when-let [creds @remote-creds]
                             (try
                               (let [cand-info (parse-candidate (.sdp candidate))
                                     req (stun/make-binding-request ice-ufrag (:ufrag creds) (:pwd creds))
                                     addr (InetSocketAddress. ^String (:ip cand-info) (int (:port cand-info)))]
                                 (when (or (.isLoopbackAddress (.getAddress addr)) (.isSiteLocalAddress (.getAddress addr)))
                                   (.send channel req addr)))
                               (catch Exception e))))
                         (onIceConnectionChange [_ state])
                         (onConnectionChange [_ state])
                         (onSignalingChange [_ state])
                         (onIceGatheringChange [_ state])
                         (onIceCandidatesRemoved [_ candidates])
                         (onAddStream [_ stream])
                         (onRemoveStream [_ stream])
                         (onDataChannel [_ channel])
                         (onRenegotiationNeeded [_])
                         (onAddTrack [_ receiver streams])
                         (onTrack [_ transceiver])
                         (onIceCandidateError [_ event]))

              pc (.createPeerConnection factory config observer)
              dc-init (dev.onvoid.webrtc.RTCDataChannelInit.)
              _ (.createDataChannel pc "test" dc-init)]

          (try
            (let [offer (create-offer pc)]
              (reset! remote-creds (extract-ice-credentials (.sdp offer)))
              (set-local-description pc offer)

              (let [sdp-str (str "v=0\r\n"
                                 "o=- 123456789 2 IN IP4 " local-ip "\r\n"
                                 "s=-\r\n"
                                 "t=0 0\r\n"
                                 "a=group:BUNDLE 0\r\n"
                                 "m=application " port " UDP/DTLS/SCTP webrtc-datachannel\r\n"
                                 "c=IN IP4 " local-ip "\r\n"
                                 "a=setup:passive\r\n"
                                 "a=mid:0\r\n"
                                 "a=sctp-port:5000\r\n"
                                 "a=fingerprint:sha-256 " server-cert-fingerprint "\r\n"
                                 "a=ice-ufrag:" ice-ufrag "\r\n"
                                 "a=ice-pwd:" ice-pwd "\r\n"
                                 "a=ice-lite\r\n"
                                 "a=rtcp-mux\r\n")
                    answer (RTCSessionDescription. RTCSdpType/ANSWER sdp-str)]

                (set-remote-description pc answer)

                (let [candidate-str (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")]
                  (.addIceCandidate pc (RTCIceCandidate. "0" 0 candidate-str)))

                (println "Waiting for STUN Binding Request from Java...")
                (is (loop [i 0]
                      (if (or @stun-received-at-clj (>= i 30))
                        @stun-received-at-clj
                        (do (Thread/sleep 1000) (recur (inc i)))))
                    "Clojure did not receive any STUN Binding Request from Java")))

            (finally
              (reset! running false)
              (try (.close channel) (catch Exception _))
              (try (.close selector) (catch Exception _))
              (.close pc)
              (.dispose factory)
              (.dispose adm)))))))
