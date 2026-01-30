(ns datachannel.webrtc-integration-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as dc]
            [datachannel.stun :as stun])
  (:import [dev.onvoid.webrtc PeerConnectionFactory RTCConfiguration PeerConnectionObserver RTCDataChannelObserver RTCIceServer RTCDataChannelInit RTCIceCandidate RTCDataChannelBuffer]
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
  ;; candidate:2498732755 1 udp 2122260223 192.168.0.2 48355 typ host ...
  (let [parts (.split candidate-sdp " ")]
    {:ip (get parts 4)
     :port (Integer/parseInt (get parts 5))}))

(defn create-offer [pc]
  (let [p (promise)]
    (.createOffer pc (dev.onvoid.webrtc.RTCOfferOptions.)
                  (reify dev.onvoid.webrtc.CreateSessionDescriptionObserver
                    (onSuccess [_ description]
                      (deliver p description))
                    (onFailure [_ error]
                      (deliver p (ex-info "Create offer failed" {:error error})))))
    @p))

(defn set-local-description [pc description]
  (let [p (promise)]
    (.setLocalDescription pc description
                          (reify dev.onvoid.webrtc.SetSessionDescriptionObserver
                            (onSuccess [_]
                              (deliver p true))
                            (onFailure [_ error]
                              (deliver p (ex-info "Set local description failed" {:error error})))))
    @p))

(defn set-remote-description [pc description]
  (let [p (promise)]
    (.setRemoteDescription pc description
                           (reify dev.onvoid.webrtc.SetSessionDescriptionObserver
                             (onSuccess [_]
                               (deliver p true))
                             (onFailure [_ error]
                               (deliver p (ex-info "Set remote description failed" {:error error})))))
    @p))

(deftest test-webrtc-data-channel
  (println "Initializing PeerConnectionFactory...")
  (let [adm (HeadlessAudioDeviceModule.)
        factory (PeerConnectionFactory. adm)
        config (RTCConfiguration.)
        ice-servers (ArrayList.)]

    ;; Use Google STUN server
    (let [ice-server (RTCIceServer.)]
      (set! (.urls ice-server) (doto (ArrayList.) (.add "stun:stun.l.google.com:19302")))
      (.add ice-servers ice-server))
    (set! (.iceServers config) ice-servers)

    (let [pc-promise (promise)
          candidates (atom [])
          remote-creds (atom nil)

          ;; Start Datachannel-clj server
          local-ip (get-local-ip)
          port (+ 20000 (rand-int 5000))
          ice-ufrag "testufrag"
          ice-pwd "testpwd"
          server (dc/listen port :host local-ip :ice-ufrag ice-ufrag :ice-pwd ice-pwd)
          server-cert-fingerprint (:fingerprint (:cert-data server))
          server-message-promise (promise)

          observer (reify PeerConnectionObserver
                     (onIceCandidate [_ candidate]
                       (println "Gathered candidate:" (.sdp candidate))
                       (swap! candidates conj candidate)
                       ;; Send STUN Binding Request to this candidate to punch hole
                       (when-let [creds @remote-creds]
                         (try
                           (let [cand-info (parse-candidate (.sdp candidate))
                                 req (stun/make-binding-request ice-ufrag (:ufrag creds) (:pwd creds))
                                 addr (InetSocketAddress. ^String (:ip cand-info) (int (:port cand-info)))]
                             (println "Sending STUN Binding Request to" addr)
                             (.send (:channel server) req addr))
                           (catch Exception e
                             (println "Failed to send STUN request:" e)))))
                     (onIceConnectionChange [_ state]
                       (println "ICE Connection State:" state))
                     (onConnectionChange [_ state]
                       (println "Connection State:" state))
                     (onSignalingChange [_ state])
                     (onIceGatheringChange [_ state])
                     (onIceCandidatesRemoved [_ candidates])
                     (onAddStream [_ stream])
                     (onRemoveStream [_ stream])
                     (onDataChannel [_ channel])
                     (onRenegotiationNeeded [_])
                     (onAddTrack [_ receiver streams])
                     (onTrack [_ transceiver])
                     (onIceCandidateError [_ event]
                       (println "ICE Candidate Error:" (.address event) (.port event) (.errorText event))))

          pc (.createPeerConnection factory config observer)

          dc-init (RTCDataChannelInit.)
          _ (set! (.ordered dc-init) true)
          data-channel (.createDataChannel pc "test" dc-init)

          dc-open-promise (promise)
          dc-message-promise (promise)

          dc-observer (reify RTCDataChannelObserver
                        (onBufferedAmountChange [_ amount])
                        (onStateChange [_]
                          (println "DataChannel State:" (.state data-channel))
                          (when (= (.state data-channel) dev.onvoid.webrtc.RTCDataChannelState/OPEN)
                            (deliver dc-open-promise true)))
                        (onMessage [_ buffer]
                          (let [data (.data buffer)
                                bytes (byte-array (.remaining data))]
                            (.get data bytes)
                            (deliver dc-message-promise (String. bytes "UTF-8")))))

          _ (.registerObserver data-channel dc-observer)]

      (println "Server started on" local-ip ":" port "Fingerprint:" server-cert-fingerprint "IP:" local-ip)

      (reset! (:on-message server)
              (fn [msg]
                (let [s (String. msg "UTF-8")]
                  (deliver server-message-promise s)
                  (dc/send-msg server "Pong from clj"))))

      (try
        ;; 1. Create Offer
        (let [offer (create-offer pc)]
          (println "Offer created:\n" (.sdp offer))
          (reset! remote-creds (extract-ice-credentials (.sdp offer)))
          (println "Remote Creds:" @remote-creds)

          ;; 2. Set Local Description
          (set-local-description pc offer)
          (println "Local Description set")

          (let [sdp-type dev.onvoid.webrtc.RTCSdpType/ANSWER
                ;; Construct minimal SDP Answer
                sdp-str (str "v=0\r\n"
                             "o=- 123456789 2 IN IP4 " local-ip "\r\n"
                             "s=-\r\n"
                             "t=0 0\r\n"
                             "a=group:BUNDLE 0\r\n"
                             "m=application " port " UDP/DTLS/SCTP webrtc-datachannel\r\n"
                             "c=IN IP4 " local-ip "\r\n"
                             "a=setup:passive\r\n"
                             "a=mid:0\r\n"
                             "a=sctp-port:5000\r\n"
                             "a=max-message-size:100000\r\n"
                             "a=fingerprint:sha-256 " server-cert-fingerprint "\r\n"
                             "a=ice-ufrag:" ice-ufrag "\r\n"
                             "a=ice-pwd:" ice-pwd "\r\n"
                             "a=ice-lite\r\n"
                             "a=rtcp-mux\r\n")

                 answer (dev.onvoid.webrtc.RTCSessionDescription. sdp-type sdp-str)]

            (println "Setting Remote Description (Answer)...")
            (println "Answer SDP:\n" sdp-str)
            (set-remote-description pc answer)
            (println "Remote Description set.")

            (let [candidate-str (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")]
              (println "Adding ICE Candidate:" candidate-str)
              (.addIceCandidate pc (RTCIceCandidate. "0" 0 candidate-str)))

            ;; Now wait for connection
            (println "Waiting for DataChannel OPEN...")
            (is (deref dc-open-promise 10000 false) "DataChannel failed to open")

            (when (realized? dc-open-promise)
              ;; Send message from Java -> Clj
              (println "Sending 'Hello from Java'...")
              (let [buffer (ByteBuffer/wrap (.getBytes "Hello from Java" "UTF-8"))
                    dc-buffer (RTCDataChannelBuffer. buffer false)]
                (.send data-channel dc-buffer))

              ;; Verify receipt at Clj
              (is (= "Hello from Java" (deref server-message-promise 5000 :timeout)))

              ;; Verify receipt at Java (Pong)
              (is (= "Pong from clj" (deref dc-message-promise 5000 :timeout))))))

        (finally
          (.close pc)
          (.dispose factory)
          (.dispose adm))))))
