(ns check-webrtc
  (:import [dev.onvoid.webrtc PeerConnectionFactory]
           [dev.onvoid.webrtc.media.audio HeadlessAudioDeviceModule]))

(defn check []
  (try
    (println "Instantiating HeadlessAudioDeviceModule...")
    (let [adm (HeadlessAudioDeviceModule.)]
      (println "Instantiating PeerConnectionFactory...")
      (let [factory (PeerConnectionFactory. adm)]
        (println "Success:" factory)
        (.dispose factory)
        (.dispose adm)))
    (catch Throwable e
      (println "Failed:" e)
      (.printStackTrace e)
      (System/exit 1))))

(check)
(System/exit 0)
