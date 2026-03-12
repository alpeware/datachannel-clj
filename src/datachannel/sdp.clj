(ns datachannel.sdp
  (:import [java.security SecureRandom]))

(defn generate-ice-credentials
  "Generates a secure ICE ufrag and pwd. The pwd must be at least 22 characters long (RFC 5245)."
  []
  (let [random (SecureRandom.)
        chars "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        len (count chars)
        gen-str (fn [n]
                  (apply str (repeatedly n #(nth chars (.nextInt random len)))))]
    {:ufrag (gen-str 8)
     :pwd   (gen-str 24)}))

(defn parse-candidate
  "Extracts the IP and port from an ICE candidate string."
  [^String candidate-str]
  (let [parts (.split candidate-str " ")]
    {:ip   (get parts 4)
     :port (Integer/parseInt (get parts 5))}))

(defn format-answer
  "Generates a complete SDP answer string."
  [{:keys [local-ip port ice-ufrag ice-pwd fingerprint setup ice-lite?]
    :or   {setup "passive"}}]
  (str "v=0\r\n"
       "o=- 123456789 2 IN IP4 " local-ip "\r\n"
       "s=-\r\n"
       "t=0 0\r\n"
       "a=group:BUNDLE 0\r\n"
       "m=application " port " UDP/DTLS/SCTP webrtc-datachannel\r\n"
       "c=IN IP4 " local-ip "\r\n"
       "a=setup:" setup "\r\n"
       "a=mid:0\r\n"
       "a=max-message-size:100000\r\n"
       "a=sctp-port:5000\r\n"
       "a=fingerprint:sha-256 " fingerprint "\r\n"
       "a=ice-ufrag:" ice-ufrag "\r\n"
       "a=ice-pwd:" ice-pwd "\r\n"
       (if ice-lite? "a=ice-lite\r\n" "")
       "a=rtcp-mux\r\n"))

(defn format-offer
  "Generates a complete SDP offer string."
  [{:keys [local-ip port ice-ufrag ice-pwd fingerprint setup ice-lite?]
    :or   {setup "actpass"}}]
  (str "v=0\r\n"
       "o=- 123456789 2 IN IP4 " local-ip "\r\n"
       "s=-\r\n"
       "t=0 0\r\n"
       "a=group:BUNDLE 0\r\n"
       "m=application " port " UDP/DTLS/SCTP webrtc-datachannel\r\n"
       "c=IN IP4 " local-ip "\r\n"
       "a=setup:" setup "\r\n"
       "a=mid:0\r\n"
       "a=max-message-size:100000\r\n"
       "a=sctp-port:5000\r\n"
       "a=fingerprint:sha-256 " fingerprint "\r\n"
       "a=ice-ufrag:" ice-ufrag "\r\n"
       "a=ice-pwd:" ice-pwd "\r\n"
       (if ice-lite? "a=ice-lite\r\n" "")
       "a=rtcp-mux\r\n"))
