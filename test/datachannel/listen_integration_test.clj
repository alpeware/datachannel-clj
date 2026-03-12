(ns datachannel.listen-integration-test
  (:require [clojure.test :refer [deftest is]]
            [datachannel.api :as api]
            [datachannel.sdp :as sdp])
  (:import [dev.onvoid.webrtc PeerConnectionFactory RTCConfiguration PeerConnectionObserver RTCIceServer RTCIceCandidate RTCSessionDescription RTCSdpType]
           [dev.onvoid.webrtc.media.audio HeadlessAudioDeviceModule]
           [java.util ArrayList]
           [java.net InetAddress]
           [java.nio ByteBuffer]))

(defn get-local-ip []
  (try
    (let [socket (java.net.DatagramSocket.)]
      (.connect socket (java.net.InetAddress/getByName "8.8.8.8") 10002)
      (let [ip (.getHostAddress (.getLocalAddress socket))]
        (.close socket)
        ip))
    (catch Exception _ "127.0.0.1")))

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

(deftest test-listen-integration
  (println "Starting full Bootstrap Server Integration Test...")
  (let [adm (HeadlessAudioDeviceModule.)
        factory (PeerConnectionFactory. adm)
        config (RTCConfiguration.)
        ice-servers (ArrayList.)]
    (let [ice-server (RTCIceServer.)]
      (set! (.urls ice-server) (doto (ArrayList.) (.add "stun:stun.l.google.com:19302")))
      (.add ice-servers ice-server))
    (set! (.iceServers config) ice-servers)

    (let [ice-connected (promise)
          sctp-connected (promise)
          msg-received (promise)
          remote-creds (atom nil)
          local-ip (get-local-ip)
          port (+ 40000 (rand-int 5000))

          ;; Boot the listener
          listener-node (api/listen! {:port port :ice-lite? true}
                                     {:on-connection
                                      (fn [child-node]
                                        (println "Bootstrap Server got a NEW connection from:" @(:remote-addr child-node))
                                        (api/set-callbacks! child-node
                                                            {:on-ice-connection-state-change
                                                             (fn [evt]
                                                               (println "Server child ICE state:" (:state evt))
                                                               (when (= (:state evt) :connected)
                                                                 (when-not (realized? ice-connected)
                                                                   (deliver ice-connected true))))
                                                             :on-open
                                                             (fn [evt]
                                                               (println "Server child SCTP Data Channel open:" evt)
                                                               (when-not (realized? sctp-connected)
                                                                 (deliver sctp-connected true)))
                                                             :on-message
                                                             (fn [evt]
                                                               (println "Server child received message!")
                                                               (deliver msg-received (String. ^bytes (:payload evt) "UTF-8")))}))})
          ice-ufrag (:ufrag (:ice-creds listener-node))
          ice-pwd (:pwd (:ice-creds listener-node))
          server-cert-fingerprint (:fingerprint (:cert-data listener-node))]

      (try
        (let [java-dc (atom nil)
              remote-candidate-ip (atom nil)
              remote-candidate-port (atom nil)
              observer (reify PeerConnectionObserver
                         (onIceCandidate [_ candidate]
                           (try
                             (let [cand-info (sdp/parse-candidate (.sdp candidate))]
                               (reset! remote-candidate-ip (:ip cand-info))
                               (reset! remote-candidate-port (:port cand-info)))
                             (catch Exception _e)))
                         (onIceConnectionChange [_ state]
                           (println "Java Client ICE State:" state))
                         (onConnectionChange [_ state]
                           (println "Java Client Connection State:" state))
                         (onSignalingChange [_ _state])
                         (onIceGatheringChange [_ _state])
                         (onIceCandidatesRemoved [_ _candidates])
                         (onAddStream [_ _stream])
                         (onRemoveStream [_ _stream])
                         (onDataChannel [_ ch]
                           (reset! java-dc ch))
                         (onRenegotiationNeeded [_])
                         (onAddTrack [_ _receiver _streams])
                         (onTrack [_ _transceiver])
                         (onIceCandidateError [_ _event]))
              pc (.createPeerConnection factory config observer)
              dc-init (dev.onvoid.webrtc.RTCDataChannelInit.)]
          (set! (.ordered dc-init) true)
          (set! (.negotiated dc-init) false)

          (let [ch (.createDataChannel pc "test" dc-init)]
            (.registerObserver ch (reify dev.onvoid.webrtc.RTCDataChannelObserver
                                    (onStateChange [_]
                                      (when (= dev.onvoid.webrtc.RTCDataChannelState/OPEN (.getState ch))
                                        (println "Java Client Data Channel is OPEN!")))
                                    (onMessage [_ _buffer]
                                      (println "Java Client received message!"))
                                    (onBufferedAmountChange [_ _amount])))
            (reset! java-dc ch))

          (let [offer (create-offer pc)]
            (reset! remote-creds (extract-ice-credentials (.sdp offer)))
            (set-local-description pc offer)

            (let [sdp-str (sdp/format-answer
                           {:local-ip    local-ip
                            :port        port
                            :ice-ufrag   ice-ufrag
                            :ice-pwd     ice-pwd
                            :fingerprint server-cert-fingerprint
                            :ice-lite?   true})
                  answer (RTCSessionDescription. RTCSdpType/ANSWER sdp-str)]

              (set-remote-description pc answer)

              (let [candidate-str (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")]
                (.addIceCandidate pc (RTCIceCandidate. "0" 0 candidate-str))))

            (println "Waiting for Server Child ICE Connected state...")
            (let [v (deref ice-connected 10000 :timeout)]
              (is (= v true) "Server child ICE state should reach :connected"))

            (println "Waiting for Server Child SCTP connected...")
            (let [v (deref sctp-connected 10000 :timeout)]
              (is (= v true) "Server child SCTP should connect"))

            (println "Sending DataChannel Message from Java Client -> Server Child")
            (let [msg-bytes (.getBytes "Hello from Bootstrap Client!" "UTF-8")
                  buf (dev.onvoid.webrtc.RTCDataChannelBuffer. (ByteBuffer/wrap msg-bytes) false)]
              (.send @java-dc buf))

            (println "Waiting for Message at Server Child...")
            (let [v (deref msg-received 5000 :timeout)]
              (is (= v "Hello from Bootstrap Client!") "Message should match"))))
        (finally
          (api/close! listener-node)
          (try (.dispose factory) (catch Exception _))
          (try (.dispose adm) (catch Exception _)))))))
