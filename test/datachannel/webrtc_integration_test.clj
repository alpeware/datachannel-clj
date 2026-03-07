(ns datachannel.webrtc-integration-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as dc]
            [datachannel.stun :as stun])
  (:import [dev.onvoid.webrtc PeerConnectionFactory RTCConfiguration PeerConnectionObserver RTCDataChannelObserver RTCIceServer RTCDataChannelInit RTCIceCandidate RTCDataChannelBuffer RTCSessionDescription RTCSdpType RTCOfferOptions]
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

(deftest test-webrtc-data-channel
  (println "Starting WebRTC integration test...")
  (let [adm (HeadlessAudioDeviceModule.)
        factory (PeerConnectionFactory. adm)
        config (RTCConfiguration.)
        ice-servers (ArrayList.)]
    (set! (.iceServers config) ice-servers)

    (let [local-ip (get-local-ip)
          port (+ 20000 (rand-int 5000))
          ice-ufrag "testufrag"
          ice-pwd "testpassword12345678901234567890" ;; Must be at least 22 chars

          ;; Clojure server (passive)
          server (dc/listen port :host local-ip :ice-ufrag ice-ufrag :ice-pwd ice-pwd :dtls-client false)
          server-cert-fingerprint (:fingerprint (:cert-data server))

          remote-creds (atom nil)
          dc-open-promise (promise)
          server-message-promise (promise)
          dc-message-promise (promise)

          observer (reify PeerConnectionObserver
                     (onIceCandidate [_ candidate]
                       (println "Java gathered candidate:" (.sdp candidate))
                       (when-let [creds @remote-creds]
                         (try
                           (let [cand-info (parse-candidate (.sdp candidate))
                                 req (stun/make-binding-request ice-ufrag (:ufrag creds) (:pwd creds))
                                 addr (InetSocketAddress. ^String (:ip cand-info) (int (:port cand-info)))]
                             (println "Clojure sending STUN Request to Java at" addr)
                             (.send (:channel server) req addr))
                           (catch Exception e (println "Error sending STUN:" e)))))
                     (onIceConnectionChange [_ state] (println "Java ICE Connection State:" state))
                     (onConnectionChange [_ state] (println "Java Connection State:" state))
                     (onDataChannel [_ _])
                     (onSignalingChange [_ _]) (onIceGatheringChange [_ _]) (onIceCandidatesRemoved [_ _])
                     (onAddStream [_ _]) (onRemoveStream [_ _]) (onRenegotiationNeeded [_])
                     (onAddTrack [_ _ _]) (onTrack [_ _]) (onIceCandidateError [_ _]))

          pc (.createPeerConnection factory config observer)
          dc-init (RTCDataChannelInit.)
          _ (set! (.ordered dc-init) true)
          data-channel (.createDataChannel pc "test" dc-init)

          _ (.registerObserver data-channel (reify RTCDataChannelObserver
                                              (onBufferedAmountChange [_ _])
                                              (onStateChange [_]
                                                (println "Java DataChannel State:" (.getState data-channel))
                                                (when (= (.getState data-channel) dev.onvoid.webrtc.RTCDataChannelState/OPEN)
                                                  (deliver dc-open-promise true)))
                                              (onMessage [_ buffer]
                                                (let [data (.data buffer)
                                                      bytes (byte-array (.remaining data))]
                                                  (.get data bytes)
                                                  (let [s (String. bytes "UTF-8")]
                                                    (println "Java received message:" s)
                                                    (deliver dc-message-promise s))))))]

      (swap! (:state server) assoc :on-message
              (fn [msg]
                (let [s (String. msg "UTF-8")]
                  (println "Clojure received message:" s)
                  (deliver server-message-promise s)
                  (dc/send-msg server "Pong from clj"))))

      (try
        (let [offer-p (promise)]
          (.createOffer pc (RTCOfferOptions.)
                        (reify dev.onvoid.webrtc.CreateSessionDescriptionObserver
                          (onSuccess [_ desc] (deliver offer-p desc))
                          (onFailure [_ err] (deliver offer-p err))))
          (let [offer @offer-p]
            (is (instance? RTCSessionDescription offer) "Offer creation failed")
            (reset! remote-creds (extract-ice-credentials (.sdp offer)))

            (.setLocalDescription pc offer (reify dev.onvoid.webrtc.SetSessionDescriptionObserver
                                             (onSuccess [_]) (onFailure [_ err] (println "SetLocal failed:" err))))

            (let [answer-sdp (str "v=0\r\n"
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
                                  "a=ice-lite\r\n")
                  answer (RTCSessionDescription. RTCSdpType/ANSWER answer-sdp)]

              (.setRemoteDescription pc answer (reify dev.onvoid.webrtc.SetSessionDescriptionObserver
                                                 (onSuccess [_]) (onFailure [_ err] (println "SetRemote failed:" err))))

              (let [cand-str (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")]
                (.addIceCandidate pc (RTCIceCandidate. "0" 0 cand-str)))

              (println "Waiting for DataChannel OPEN...")
              (if (deref dc-open-promise 20000 false)
                (do
                  (println "DataChannel OPEN! Sending 'Hello from Java'...")
                  (let [buf (ByteBuffer/wrap (.getBytes "Hello from Java" "UTF-8"))
                        dc-buf (RTCDataChannelBuffer. buf false)]
                    (.send data-channel dc-buf))

                  (is (= "Hello from Java" (deref server-message-promise 10000 :timeout)))
                  (is (= "Pong from clj" (deref dc-message-promise 10000 :timeout))))
                (is false "DataChannel failed to open (timeout)")))))
        (finally
          (dc/close server)
          (.close pc)
          (.dispose factory)
          (.dispose adm))))))
