(ns datachannel.webrtc-integration-test
  (:require [clojure.test :refer [deftest is]]
            [datachannel.api :as api]
            [datachannel.stun :as stun]
            [datachannel.sdp :as sdp])
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

    (let [ice-candidate-received (promise)
          ice-connected (promise)
          sctp-connected (promise)
          msg-received (promise)
          remote-creds (atom nil)
          local-ip (get-local-ip)
          port (+ 35000 (rand-int 5000))

          node (api/create-node {:port port :setup "passive"})
          ice-ufrag (:ufrag (:ice-creds node))
          ice-pwd (:pwd (:ice-creds node))
          server-cert-fingerprint (:fingerprint (:cert-data node))

          callbacks {:on-ice-candidate (fn [evt]
                                         (println "Clojure got ICE candidate:" evt)
                                         (when-not (realized? ice-candidate-received)
                                           (deliver ice-candidate-received true)))
                     :on-ice-connection-state-change (fn [evt]
                                                       (println "Clojure ICE connection state change:" (:state evt))
                                                       (when (= (:state evt) :connected)
                                                         (when-not (realized? ice-connected)
                                                           (deliver ice-connected true))))
                     :on-open (fn [evt]
                                (println "Clojure SCTP data channel open:" evt)
                                (when-not (realized? sctp-connected)
                                  (deliver sctp-connected true)))
                     :on-message (fn [evt]
                                   (println "Clojure received message!")
                                   (deliver msg-received (String. ^bytes (:payload evt) "UTF-8")))}]

      (try
        ;; Setup Observer and PC
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
                           (println "Java ICE State:" state))
                         (onConnectionChange [_ state]
                           (println "Java Connection State:" state))
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
                                        (deliver sctp-connected true)))
                                    (onMessage [_ buffer]
                                      (let [bytes (byte-array (.remaining buffer))]
                                        (.get buffer bytes)
                                        (deliver msg-received (String. bytes "UTF-8"))))
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
                            :fingerprint server-cert-fingerprint})
                  answer (RTCSessionDescription. RTCSdpType/ANSWER sdp-str)]

              (set-remote-description pc answer)

              (let [candidate-str (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")]
                (.addIceCandidate pc (RTCIceCandidate. "0" 0 candidate-str))))

            ;; Wait for remote candidate info from Java
            (println "Waiting for Java ICE candidate...")
            (loop [i 0]
              (if (or (and @remote-candidate-ip @remote-candidate-port) (> i 50))
                nil
                (do (Thread/sleep 100) (recur (inc i)))))

            (future
              (loop [i 0]
                (if (or (realized? sctp-connected) (> i 50))
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
                          (when-let [channel @(:channel node)]
                            (try (.send channel req addr)
                                 (catch Exception _))))
                        (catch Exception _e)))
                    (Thread/sleep 100)
                    (recur (inc i))))))

            ;; Start Clojure API node
            (api/start! node
                        {:ip (or @remote-candidate-ip local-ip)
                         :port (or @remote-candidate-port port)}
                        callbacks)

            (println "Waiting for ICE Connected state in Clojure...")
            (let [v (deref ice-connected 10000 :timeout)]
              (is (= v true) "Clojure ICE state should reach :connected"))

            (println "Waiting for SCTP connected...")
            (let [v (deref sctp-connected 10000 :timeout)]
              (is (= v true) "SCTP should connect"))

            (println "Sending DataChannel Message from Java -> Clojure")
            (let [msg-bytes (.getBytes "Hello from Java!" "UTF-8")
                  buf (dev.onvoid.webrtc.RTCDataChannelBuffer. (ByteBuffer/wrap msg-bytes) false)]
              (.send @java-dc buf))

            (println "Waiting for Message at Clojure...")
            (let [v (deref msg-received 5000 :timeout)]
              (is (= v "Hello from Java!") "Message should match"))))
        (finally
          (api/close! node)
          (try (.dispose factory) (catch Exception _))
          (try (.dispose adm) (catch Exception _)))))))
