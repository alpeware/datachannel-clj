(ns datachannel.webrtc-java-test
  (:require [clojure.test :refer :all])
  (:import [dev.onvoid.webrtc PeerConnectionFactory RTCConfiguration PeerConnectionObserver RTCDataChannelObserver RTCDataChannelInit RTCDataChannelBuffer RTCOfferOptions RTCSdpType RTCSessionDescription RTCIceCandidate]
           [dev.onvoid.webrtc.media.audio HeadlessAudioDeviceModule]
           [java.nio ByteBuffer]
           [java.util ArrayList Collections]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defonce factory (PeerConnectionFactory. (HeadlessAudioDeviceModule.)))

(defn create-offer [pc]
  (let [p (promise)]
    (.createOffer pc (RTCOfferOptions.)
                  (reify dev.onvoid.webrtc.CreateSessionDescriptionObserver
                    (onSuccess [_ description] (deliver p description))
                    (onFailure [_ error] (deliver p (ex-info "CreateOffer failed" {:error error})))))
    @p))

(defn create-answer [pc]
  (let [p (promise)]
    (.createAnswer pc (dev.onvoid.webrtc.RTCAnswerOptions.)
                   (reify dev.onvoid.webrtc.CreateSessionDescriptionObserver
                     (onSuccess [_ description] (deliver p description))
                     (onFailure [_ error] (deliver p (ex-info "CreateAnswer failed" {:error error})))))
    @p))

(defn set-local [pc desc]
  (let [p (promise)]
    (.setLocalDescription pc desc
                          (reify dev.onvoid.webrtc.SetSessionDescriptionObserver
                            (onSuccess [_] (deliver p true))
                            (onFailure [_ error] (deliver p (ex-info "SetLocal failed" {:error error})))))
    @p))

(defn set-remote [pc desc]
  (let [p (promise)]
    (.setRemoteDescription pc desc
                           (reify dev.onvoid.webrtc.SetSessionDescriptionObserver
                             (onSuccess [_] (deliver p true))
                             (onFailure [_ error] (deliver p (ex-info "SetRemote failed" {:error error})))))
    @p))

(deftest test-webrtc-java-text-message
  (let [caller-atom (atom nil)
        callee-atom (atom nil)

        caller-received (promise)
        callee-received (promise)

        callee-dc-promise (promise)

        caller-observer (reify PeerConnectionObserver
                          (onIceCandidate [_ candidate]
                            ;; Pass candidate to callee
                            (when-let [callee @callee-atom]
                              (.addIceCandidate callee candidate)))
                          (onIceConnectionChange [_ state] )
                          (onConnectionChange [_ state] )
                          (onSignalingChange [_ _]) (onIceGatheringChange [_ _]) (onIceCandidatesRemoved [_ _])
                          (onAddStream [_ _]) (onRemoveStream [_ _]) (onRenegotiationNeeded [_])
                          (onAddTrack [_ _ _]) (onTrack [_ _]) (onIceCandidateError [_ _])
                          (onDataChannel [_ _]))

        callee-observer (reify PeerConnectionObserver
                          (onIceCandidate [_ candidate]
                            ;; Pass candidate to caller
                            (when-let [caller @caller-atom]
                              (.addIceCandidate caller candidate)))
                          (onIceConnectionChange [_ state] )
                          (onConnectionChange [_ state] )
                          (onSignalingChange [_ _]) (onIceGatheringChange [_ _]) (onIceCandidatesRemoved [_ _])
                          (onAddStream [_ _]) (onRemoveStream [_ _]) (onRenegotiationNeeded [_])
                          (onAddTrack [_ _ _]) (onTrack [_ _]) (onIceCandidateError [_ _])
                          (onDataChannel [_ channel]
                            (deliver callee-dc-promise channel)
                            (.registerObserver channel
                                               (reify RTCDataChannelObserver
                                                 (onBufferedAmountChange [_ _])
                                                 (onStateChange [_])
                                                 (onMessage [_ buffer]
                                                   (let [data (.data buffer)
                                                         bytes (byte-array (.remaining data))]
                                                     (.get data bytes)
                                                     (deliver callee-received (String. bytes "UTF-8"))))))))

        caller (.createPeerConnection factory (RTCConfiguration.) caller-observer)
        callee (.createPeerConnection factory (RTCConfiguration.) callee-observer)]

    (reset! caller-atom caller)
    (reset! callee-atom callee)

    (try
      ;; Create DataChannel on Caller
      (let [dc-init (RTCDataChannelInit.)
            _ (set! (.ordered dc-init) true)
            dc (.createDataChannel caller "test" dc-init)]

        (.registerObserver dc
                           (reify RTCDataChannelObserver
                             (onBufferedAmountChange [_ _])
                             (onStateChange [_])
                             (onMessage [_ buffer]
                               (let [data (.data buffer)
                                     bytes (byte-array (.remaining data))]
                                 (.get data bytes)
                                 (deliver caller-received (String. bytes "UTF-8"))))))

        ;; Signaling
        (let [offer (create-offer caller)]
          (set-local caller offer)
          (set-remote callee offer)
          (let [answer (create-answer callee)]
            (set-local callee answer)
            (set-remote caller answer)))

        ;; Wait for callee to receive DataChannel
        (let [callee-dc (deref callee-dc-promise 5000 :timeout)]
          (is (not= :timeout callee-dc) "Callee did not receive DataChannel")

          (when (not= :timeout callee-dc)
            ;; Wait for channels to be open (implicitly handled by sleep or retry, but let's sleep a bit)
            (Thread/sleep 1000)

            ;; Send Caller -> Callee
            (let [msg "Hello world"
                  buf (ByteBuffer/wrap (.getBytes msg "UTF-8"))
                  dc-buf (RTCDataChannelBuffer. buf false)]
              (.send dc dc-buf))

            (is (= "Hello world" (deref callee-received 5000 :timeout)))

            ;; Send Callee -> Caller
            (let [msg "Hi :)"
                  buf (ByteBuffer/wrap (.getBytes msg "UTF-8"))
                  dc-buf (RTCDataChannelBuffer. buf false)]
              (.send callee-dc dc-buf))

            (is (= "Hi :)" (deref caller-received 5000 :timeout))))))

      (finally
        (.close caller)
        (.close callee)))))
