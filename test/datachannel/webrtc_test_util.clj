(ns datachannel.webrtc-test-util
  (:import [dev.onvoid.webrtc PeerConnectionObserver RTCOfferOptions RTCAnswerOptions SetSessionDescriptionObserver CreateSessionDescriptionObserver]
           [java.net InetAddress]
           [java.nio.channels UnsupportedAddressTypeException]))

(defn get-local-ip "Resolves the local host IP address via java.net.InetAddress for binding during tests."
  []
  (.getHostAddress (InetAddress/getLocalHost)))

(defn extract-ice-credentials "Extracts the ICE ufrag and pwd credentials from a given raw SDP string."
  [sdp]
  {:ufrag (second (re-find #"a=ice-ufrag:([^\r\n]+)" sdp))
   :pwd (second (re-find #"a=ice-pwd:([^\r\n]+)" sdp))})

(defn parse-candidate "Parses an ICE candidate SDP attribute string to extract the IP address and port."
  [candidate-sdp]
  (let [parts (.split candidate-sdp " ")]
    {:ip (get parts 4)
     :port (Integer/parseInt (get parts 5))}))

(defn create-offer "Creates a WebRTC offer asynchronously and returns a promise containing the RTCSessionDescription."
  [pc]
  (let [p (promise)]
    (.createOffer pc (RTCOfferOptions.)
                  (reify CreateSessionDescriptionObserver
                    (onSuccess [_ desc] (deliver p desc))
                    (onFailure [_ err] (deliver p (ex-info "CreateOffer failed" {:error err})))))
    @p))

(defn create-answer "Creates a WebRTC answer asynchronously and returns a promise containing the RTCSessionDescription."
  [pc]
  (let [p (promise)]
    (.createAnswer pc (RTCAnswerOptions.)
                   (reify CreateSessionDescriptionObserver
                     (onSuccess [_ desc] (deliver p desc))
                     (onFailure [_ err] (deliver p (ex-info "CreateAnswer failed" {:error err})))))
    @p))

(defn set-local-description "Sets the local RTCSessionDescription on the PeerConnection asynchronously, returning a promise that resolves to true on success."
  [pc desc]
  (let [p (promise)]
    (.setLocalDescription pc desc
                          (reify SetSessionDescriptionObserver
                            (onSuccess [_] (deliver p true))
                            (onFailure [_ err] (deliver p (ex-info "SetLocal failed" {:error err})))))
    @p))

(defn set-remote-description "Sets the remote RTCSessionDescription on the PeerConnection asynchronously, returning a promise that resolves to true on success."
  [pc desc]
  (let [p (promise)]
    (.setRemoteDescription pc desc
                           (reify SetSessionDescriptionObserver
                             (onSuccess [_] (deliver p true))
                             (onFailure [_ err] (deliver p (ex-info "SetRemote failed" {:error err})))))
    @p))

(defn make-pc-observer "Creates a java WebRTC PeerConnectionObserver that routes the onIceCandidate callback."
  [{:keys [on-ice-candidate]}]
  (reify PeerConnectionObserver
    (onIceCandidate [_ candidate] (when on-ice-candidate
                                    (try
                                      (on-ice-candidate candidate)
                                      (catch UnsupportedAddressTypeException _e nil))))
    (onIceConnectionChange [_ state] (println "Java ICE Connection State:" state))
    (onConnectionChange [_ state] (println "Java Connection State:" state))
    (onDataChannel [_ _])
    (onSignalingChange [_ _]) (onIceGatheringChange [_ _]) (onIceCandidatesRemoved [_ _])
    (onAddStream [_ _]) (onRemoveStream [_ _]) (onRenegotiationNeeded [_])
    (onAddTrack [_ _ _]) (onTrack [_ _]) (onIceCandidateError [_ _])))

(defn build-answer-sdp "Constructs a passive WebRTC SDP answer string containing ICE and DTLS attributes."
  [local-ip port fingerprint ice-ufrag ice-pwd]
  (str "v=0\r\n"
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
       "a=fingerprint:sha-256 " fingerprint "\r\n"
       "a=ice-ufrag:" ice-ufrag "\r\n"
       "a=ice-pwd:" ice-pwd "\r\n"
       "a=ice-lite\r\n"))
