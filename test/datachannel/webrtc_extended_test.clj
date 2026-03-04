(ns datachannel.webrtc-extended-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as dc]
            [datachannel.dtls :as dtls]
            [datachannel.stun :as stun]
            [datachannel.webrtc-test-util :as util])
  (:import [dev.onvoid.webrtc PeerConnectionFactory RTCConfiguration RTCDataChannelObserver RTCDataChannelInit RTCDataChannelBuffer RTCSessionDescription RTCSdpType RTCIceCandidate]
           [dev.onvoid.webrtc.media.audio HeadlessAudioDeviceModule]
           [java.util ArrayList Arrays]
           [java.net InetSocketAddress]
           [java.nio ByteBuffer]))

(deftest test-webrtc-binary-data
  (println "Starting WebRTC Binary Data test...")
  (let [adm (HeadlessAudioDeviceModule.)
        factory (PeerConnectionFactory. adm)
        config (RTCConfiguration.)
        local-ip (util/get-local-ip)
        port (+ 21000 (rand-int 1000))
        ice-ufrag "binaryufrag"
        ice-pwd "binarypassword123456789012"
        server (dc/listen port :host local-ip :ice-ufrag ice-ufrag :ice-pwd ice-pwd :dtls-client false)
        server-cert-fingerprint (:fingerprint (:cert-data server))
        remote-creds (atom nil)
        dc-open-promise (promise)
        binary-received (promise)]

    (reset! (:on-data server)
            (fn [{:keys [payload protocol]}]
              (when (= protocol :webrtc/binary)
                (deliver binary-received payload))))

    (let [observer (util/make-pc-observer
                    {:on-ice-candidate
                     (fn [candidate]
                       (when-let [creds @remote-creds]
                         (let [cand-info (util/parse-candidate (.sdp candidate))
                               req (stun/make-binding-request ice-ufrag (:ufrag creds) (:pwd creds))
                               addr (InetSocketAddress. ^String (:ip cand-info) (int (:port cand-info)))]
                           (.send (:channel server) req addr))))})
          pc (.createPeerConnection factory config observer)
          dc-init (RTCDataChannelInit.)
          dc (.createDataChannel pc "binary" dc-init)]

      (.registerObserver dc (reify RTCDataChannelObserver
                              (onBufferedAmountChange [_ _])
                              (onStateChange [_]
                                (when (= (.getState dc) dev.onvoid.webrtc.RTCDataChannelState/OPEN)
                                  (deliver dc-open-promise true)))
                              (onMessage [_ _])))

      (try
        (let [offer (util/create-offer pc)]
          (reset! remote-creds (util/extract-ice-credentials (.sdp offer)))
          (util/set-local-description pc offer)
          (let [answer-sdp (util/build-answer-sdp local-ip port server-cert-fingerprint ice-ufrag ice-pwd)
                answer (RTCSessionDescription. RTCSdpType/ANSWER answer-sdp)]
            (util/set-remote-description pc answer)
            (.addIceCandidate pc (RTCIceCandidate. "0" 0 (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")))

            (if (deref dc-open-promise 15000 false)
              (let [data (byte-array [0xDE 0xAD 0xBE 0xEF 0x00 0xFF])
                    buf (ByteBuffer/wrap data)
                    dc-buf (RTCDataChannelBuffer. buf true)] ;; true = binary
                (.send dc dc-buf)
                (let [received (deref binary-received 10000 :timeout)]
                  (is (not= :timeout received) "Binary data timeout")
                  (is (Arrays/equals ^bytes data ^bytes received) "Binary data mismatch")))
              (is false "DataChannel timeout"))))
        (finally
          (dc/close server)
          (.close pc)
          (.dispose factory)
          (.dispose adm))))))

(deftest test-webrtc-stress
  (println "Starting WebRTC Stress test (rapid messages)...")
  (let [adm (HeadlessAudioDeviceModule.)
        factory (PeerConnectionFactory. adm)
        config (RTCConfiguration.)
        local-ip (util/get-local-ip)
        port (+ 22000 (rand-int 1000))
        ice-ufrag "stressufrag"
        ice-pwd "stresspassword123456789012"
        server (dc/listen port :host local-ip :ice-ufrag ice-ufrag :ice-pwd ice-pwd :dtls-client false)
        server-cert-fingerprint (:fingerprint (:cert-data server))
        remote-creds (atom nil)
        dc-open-promise (promise)
        messages-received (atom [])
        num-messages 20]

    (reset! (:on-message server)
            (fn [msg]
              (swap! messages-received conj (String. msg "UTF-8"))))

    (let [observer (util/make-pc-observer
                    {:on-ice-candidate
                     (fn [candidate]
                       (when-let [creds @remote-creds]
                         (let [cand-info (util/parse-candidate (.sdp candidate))
                               req (stun/make-binding-request ice-ufrag (:ufrag creds) (:pwd creds))
                               addr (InetSocketAddress. ^String (:ip cand-info) (int (:port cand-info)))]
                           (.send (:channel server) req addr))))})
          pc (.createPeerConnection factory config observer)
          dc-init (RTCDataChannelInit.)
          dc (.createDataChannel pc "stress" dc-init)]

      (.registerObserver dc (reify RTCDataChannelObserver
                              (onBufferedAmountChange [_ _])
                              (onStateChange [_]
                                (when (= (.getState dc) dev.onvoid.webrtc.RTCDataChannelState/OPEN)
                                  (deliver dc-open-promise true)))
                              (onMessage [_ _])))

      (try
        (let [offer (util/create-offer pc)]
          (reset! remote-creds (util/extract-ice-credentials (.sdp offer)))
          (util/set-local-description pc offer)
          (let [answer-sdp (util/build-answer-sdp local-ip port server-cert-fingerprint ice-ufrag ice-pwd)
                answer (RTCSessionDescription. RTCSdpType/ANSWER answer-sdp)]
            (util/set-remote-description pc answer)
            (.addIceCandidate pc (RTCIceCandidate. "0" 0 (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")))

            (if (deref dc-open-promise 15000 false)
              (do
                (doseq [i (range num-messages)]
                  (let [msg (str "Msg " i)
                        buf (ByteBuffer/wrap (.getBytes msg "UTF-8"))
                        dc-buf (RTCDataChannelBuffer. buf false)]
                    (.send dc dc-buf)))

                ;; Wait for all messages
                (is (loop [attempts 0]
                      (if (or (= (count @messages-received) num-messages) (>= attempts 50))
                        (= (count @messages-received) num-messages)
                        (do (Thread/sleep 100) (recur (inc attempts)))))
                    (str "Did not receive all messages. Received: " (count @messages-received)))
                (is (= (vec (map #(str "Msg " %) (range num-messages))) @messages-received) "Message order/content mismatch"))
              (is false "DataChannel timeout"))))
        (finally
          (dc/close server)
          (.close pc)
          (.dispose factory)
          (.dispose adm))))))

(deftest test-webrtc-large-packet
  (println "Starting WebRTC Large Packet test...")
  (let [adm (HeadlessAudioDeviceModule.)
        factory (PeerConnectionFactory. adm)
        config (RTCConfiguration.)
        local-ip (util/get-local-ip)
        port (+ 23000 (rand-int 1000))
        ice-ufrag "largeufrag"
        ice-pwd "largepassword123456789012"
        server (dc/listen port :host local-ip :ice-ufrag ice-ufrag :ice-pwd ice-pwd :dtls-client false)
        server-cert-fingerprint (:fingerprint (:cert-data server))
        remote-creds (atom nil)
        dc-open-promise (promise)
        large-msg-received (promise)
        large-data (byte-array 1000)]
    (.nextBytes (java.security.SecureRandom.) large-data)

    (reset! (:on-data server)
            (fn [{:keys [payload]}]
              (deliver large-msg-received payload)))

    (let [observer (util/make-pc-observer
                    {:on-ice-candidate
                     (fn [candidate]
                       (when-let [creds @remote-creds]
                         (let [cand-info (util/parse-candidate (.sdp candidate))
                               req (stun/make-binding-request ice-ufrag (:ufrag creds) (:pwd creds))
                               addr (InetSocketAddress. ^String (:ip cand-info) (int (:port cand-info)))]
                           (.send (:channel server) req addr))))})
          pc (.createPeerConnection factory config observer)
          dc-init (RTCDataChannelInit.)
          dc (.createDataChannel pc "large" dc-init)]

      (.registerObserver dc (reify RTCDataChannelObserver
                              (onBufferedAmountChange [_ _])
                              (onStateChange [_]
                                (when (= (.getState dc) dev.onvoid.webrtc.RTCDataChannelState/OPEN)
                                  (deliver dc-open-promise true)))
                              (onMessage [_ _])))

      (try
        (let [offer (util/create-offer pc)]
          (reset! remote-creds (util/extract-ice-credentials (.sdp offer)))
          (util/set-local-description pc offer)
          (let [answer-sdp (util/build-answer-sdp local-ip port server-cert-fingerprint ice-ufrag ice-pwd)
                answer (RTCSessionDescription. RTCSdpType/ANSWER answer-sdp)]
            (util/set-remote-description pc answer)
            (.addIceCandidate pc (RTCIceCandidate. "0" 0 (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")))

            (if (deref dc-open-promise 15000 false)
              (let [dc-buf (RTCDataChannelBuffer. (ByteBuffer/wrap large-data) true)]
                (.send dc dc-buf)
                (let [received (deref large-msg-received 10000 :timeout)]
                  (is (not= :timeout received) "Large message timeout")
                  (is (Arrays/equals ^bytes large-data ^bytes received) "Large data mismatch")))
              (is false "DataChannel timeout"))))
        (finally
          (dc/close server)
          (.close pc)
          (.dispose factory)
          (.dispose adm))))))

(deftest test-webrtc-multiple-channels
  (println "Starting WebRTC Multiple Channels test...")
  (let [adm (HeadlessAudioDeviceModule.)
        factory (PeerConnectionFactory. adm)
        config (RTCConfiguration.)
        local-ip (util/get-local-ip)
        port (+ 24000 (rand-int 1000))
        ice-ufrag "multiufrag"
        ice-pwd "multipassword123456789012"
        server (dc/listen port :host local-ip :ice-ufrag ice-ufrag :ice-pwd ice-pwd :dtls-client false)
        server-cert-fingerprint (:fingerprint (:cert-data server))
        remote-creds (atom nil)
        dc1-open-promise (promise)
        dc2-open-promise (promise)
        messages (atom {})]

    (reset! (:on-data server)
            (fn [{:keys [payload stream-id]}]
              (swap! messages assoc stream-id (String. payload "UTF-8"))))

    (let [observer (util/make-pc-observer
                    {:on-ice-candidate
                     (fn [candidate]
                       (when-let [creds @remote-creds]
                         (let [cand-info (util/parse-candidate (.sdp candidate))
                               req (stun/make-binding-request ice-ufrag (:ufrag creds) (:pwd creds))
                               addr (InetSocketAddress. ^String (:ip cand-info) (int (:port cand-info)))]
                           (.send (:channel server) req addr))))})
          pc (.createPeerConnection factory config observer)

          dc1-init (RTCDataChannelInit.)
          _ (set! (.id dc1-init) 10)
          dc1 (.createDataChannel pc "chan1" dc1-init)

          dc2-init (RTCDataChannelInit.)
          _ (set! (.id dc2-init) 20)
          dc2 (.createDataChannel pc "chan2" dc2-init)]

      (.registerObserver dc1 (reify RTCDataChannelObserver
                               (onBufferedAmountChange [_ _])
                               (onStateChange [_]
                                 (when (= (.getState dc1) dev.onvoid.webrtc.RTCDataChannelState/OPEN)
                                   (deliver dc1-open-promise true)))
                               (onMessage [_ _])))

      (.registerObserver dc2 (reify RTCDataChannelObserver
                               (onBufferedAmountChange [_ _])
                               (onStateChange [_]
                                 (when (= (.getState dc2) dev.onvoid.webrtc.RTCDataChannelState/OPEN)
                                   (deliver dc2-open-promise true)))
                               (onMessage [_ _])))

      (try
        (let [offer (util/create-offer pc)]
          (reset! remote-creds (util/extract-ice-credentials (.sdp offer)))
          (util/set-local-description pc offer)
          (let [answer-sdp (util/build-answer-sdp local-ip port server-cert-fingerprint ice-ufrag ice-pwd)
                answer (RTCSessionDescription. RTCSdpType/ANSWER answer-sdp)]
            (util/set-remote-description pc answer)
            (.addIceCandidate pc (RTCIceCandidate. "0" 0 (str "candidate:1 1 UDP 2130706431 " local-ip " " port " typ host")))

            (if (and (deref dc1-open-promise 15000 false)
                     (deref dc2-open-promise 15000 false))
              (do
                (.send dc1 (RTCDataChannelBuffer. (ByteBuffer/wrap (.getBytes "Hello 1" "UTF-8")) false))
                (.send dc2 (RTCDataChannelBuffer. (ByteBuffer/wrap (.getBytes "Hello 2" "UTF-8")) false))

                (is (loop [attempts 0]
                      (if (or (= (count @messages) 2) (>= attempts 100))
                        (= (count @messages) 2)
                        (do (Thread/sleep 100) (recur (inc attempts)))))
                    (str "Did not receive messages from both channels. Received: " (keys @messages)))
                ;; Use whatever IDs were assigned by the implementation
                (is (= 2 (count (set (keys @messages)))) "Did not receive from 2 distinct streams")
                (is (some #{"Hello 1"} (vals @messages)))
                (is (some #{"Hello 2"} (vals @messages))))
              (is false "DataChannels timeout"))))
        (finally
          (dc/close server)
          (.close pc)
          (.dispose factory)
          (.dispose adm))))))
