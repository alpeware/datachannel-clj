(ns datachannel.stun
  (:import [java.nio ByteBuffer ByteOrder]
           [java.net InetSocketAddress]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util.zip CRC32]
           [java.security SecureRandom]))

(defonce ^:private secure-rand (SecureRandom.))

(def magic-cookie
  "RFC 5389 compliant STUN magic cookie (0x2112A442) indicating protocol version 2."
  0x2112A442)

;; Attributes
(def ATTR_USERNAME
  "RFC 5389 STUN attribute code for USERNAME (0x0006)."
  0x0006)
(def ATTR_MESSAGE_INTEGRITY
  "RFC 5389 STUN attribute code for MESSAGE-INTEGRITY (0x0008)."
  0x0008)
(def ATTR_ERROR_CODE
  "RFC 5389 STUN attribute code for ERROR-CODE (0x0009)."
  0x0009)
(def ATTR_UNKNOWN_ATTRIBUTES
  "RFC 5389 STUN attribute code for UNKNOWN-ATTRIBUTES (0x000A)."
  0x000A)
(def ATTR_XOR_MAPPED_ADDRESS
  "RFC 5389 STUN attribute code for XOR-MAPPED-ADDRESS (0x0020)."
  0x0020)
(def ATTR_PRIORITY
  "RFC 5245 ICE STUN attribute code for PRIORITY (0x0024)."
  0x0024)
(def ATTR_USE_CANDIDATE
  "RFC 5245 ICE STUN attribute code for USE-CANDIDATE (0x0025)."
  0x0025)
(def ATTR_FINGERPRINT
  "RFC 5389 STUN attribute code for FINGERPRINT (0x8028)."
  0x8028)
(def ATTR_ICE_CONTROLLED
  "RFC 5245 ICE STUN attribute code for ICE-CONTROLLED (0x8029)."
  0x8029)
(def ATTR_ICE_CONTROLLING
  "RFC 5245 ICE STUN attribute code for ICE-CONTROLLING (0x802A)."
  0x802A)

(def DEFAULT_PRIORITY
  "A default integer priority value provided for ICE agent candidate comparisons."
  1853824767)

(defn- put-unsigned-short [^ByteBuffer buf val]
  (.putShort buf (unchecked-short val)))

(defn- get-unsigned-short [^ByteBuffer buf]
  (bit-and (.getShort buf) 0xffff))

(defn- get-unsigned-byte [^ByteBuffer buf]
  (bit-and (.get buf) 0xff))

(defn- compute-hmac-sha1 [key data]
  (let [mac (Mac/getInstance "HmacSHA1")
        key-spec (SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA1")]
    (.init mac key-spec)
    (.doFinal mac data)))

(defn- compute-crc32 [data]
  (let [crc (CRC32.)]
    (.update crc data)
    (bit-xor (.getValue crc) 0x5354554e)))

(defn make-binding-request
  "Constructs an RFC 5389 STUN Binding Request packet containing USERNAME, PRIORITY, ICE-CONTROLLED, MESSAGE-INTEGRITY (signed using HMAC-SHA1 and `remote-pwd`), and a CRC32c FINGERPRINT."
  [local-ufrag remote-ufrag remote-pwd]
  (let [buf (ByteBuffer/allocate 1024)
        _ (.order buf ByteOrder/BIG_ENDIAN)
        tx-id (byte-array 12)]
    (.nextBytes secure-rand tx-id)

    ;; Header
    (put-unsigned-short buf 0x0001) ;; Binding Request
    (put-unsigned-short buf 0) ;; Length placeholder
    (.putInt buf magic-cookie)
    (.put buf tx-id)

    ;; Attributes

    ;; USERNAME
    (let [username (str remote-ufrag ":" local-ufrag)
          u-bytes (.getBytes username "UTF-8")
          len (alength u-bytes)
          padding (let [r (mod len 4)] (if (zero? r) 0 (- 4 r)))]
      (put-unsigned-short buf ATTR_USERNAME)
      (put-unsigned-short buf len)
      (.put buf u-bytes)
      (dotimes [_ padding] (.put buf (byte 0))))

    ;; PRIORITY (optional, but good practice)
    (put-unsigned-short buf ATTR_PRIORITY)
    (put-unsigned-short buf 4)
    (.putInt buf DEFAULT_PRIORITY)

    ;; ICE-CONTROLLING (or CONTROLLED)
    ;; Since we are passive/lite, we usually are controlled.
    (put-unsigned-short buf ATTR_ICE_CONTROLLED)
    (put-unsigned-short buf 8)
    (.putLong buf (.nextLong secure-rand))

    ;; Update Header Length for MI (FINGERPRINT comes after, but length in header for MI should only include MI)
    (let [len-before-mi (- (.position buf) 20)
          len-with-mi (+ len-before-mi 24)]
      (.putShort buf 2 (unchecked-short len-with-mi)))

    ;; MESSAGE-INTEGRITY
    (let [len-to-sign (.position buf)
          data-to-sign (byte-array len-to-sign)]
      (.position buf 0)
      (.get buf data-to-sign)
      (.position buf len-to-sign)

      (let [hmac (compute-hmac-sha1 remote-pwd data-to-sign)]
        (put-unsigned-short buf ATTR_MESSAGE_INTEGRITY)
        (put-unsigned-short buf 20)
        (.put buf hmac)))

    ;; FINGERPRINT
    ;; Update Header Length to include FINGERPRINT
    (let [len-before-fp (- (.position buf) 20)
          total-len (+ len-before-fp 8)]
      (.putShort buf 2 (unchecked-short total-len)))

    (let [len-to-crc (.position buf)
          data-to-crc (byte-array len-to-crc)]
      (.position buf 0)
      (.get buf data-to-crc)
      (.position buf len-to-crc)

      (let [crc (compute-crc32 data-to-crc)]
        (put-unsigned-short buf ATTR_FINGERPRINT)
        (put-unsigned-short buf 4)
        (.putInt buf (unchecked-int crc))))

    (.flip buf)
    buf))

(defn make-simple-binding-request
  "Constructs a minimal, unauthenticated STUN Binding Request lacking extensions or integrity checks."
  []
  (let [buf (ByteBuffer/allocate 1024)
        _ (.order buf ByteOrder/BIG_ENDIAN)
        tx-id (byte-array 12)]
    (.nextBytes secure-rand tx-id)

    ;; Header
    (put-unsigned-short buf 0x0001) ;; Binding Request
    (put-unsigned-short buf 0) ;; Length is 0
    (.putInt buf magic-cookie)
    (.put buf tx-id)

    (.flip buf)
    buf))

(defn decode-xor-mapped-address
  "Un-XORs the payload of an RFC 5389 XOR-MAPPED-ADDRESS attribute using the STUN `magic-cookie` and transactional ID, returning a map mapping `:family`, `:port`, and `:address`."
  [val-bytes cookie]
  (let [buf (ByteBuffer/wrap val-bytes)
        _ (.order buf ByteOrder/BIG_ENDIAN)
        _ (get-unsigned-byte buf) ;; reserved
        family (get-unsigned-byte buf)
        port (get-unsigned-short buf)]
    (if (= family 0x01)
      (let [addr-bytes (byte-array 4)]
        (.get buf addr-bytes)
        ;; XOR logic
        (let [xport (bit-xor port (bit-shift-right cookie 16))
              cookie-bytes (ByteBuffer/allocate 4)
              _ (.putInt cookie-bytes cookie)
              _ (.flip cookie-bytes)
              magic-bytes (.array cookie-bytes)
              decoded-addr (byte-array 4)]
          (dotimes [i 4]
            (aset decoded-addr i (byte (bit-xor (aget addr-bytes i) (aget magic-bytes i)))))
          {:family family :port xport :address (java.net.InetAddress/getByAddress decoded-addr)}))
      (throw (ex-info "Only IPv4 supported for now" {:family family})))))

(defn parse-packet
  "Reads an RFC 5389 STUN message byte stream, separating the header elements and extracting all TLV attributes into an unparsed map."
  [^ByteBuffer buf]
  (let [_ (.order buf ByteOrder/BIG_ENDIAN)
        msg-type (get-unsigned-short buf)
        msg-len (get-unsigned-short buf)
        cookie (.getInt buf)
        tx-id (byte-array 12)]
    (.get buf tx-id)
    ;; Loop through attributes
    (loop [attrs {}]
      (if (< (.position buf) (.limit buf))
        (let [attr-type (get-unsigned-short buf)
              attr-len (get-unsigned-short buf)
               ;; Read value
              val-bytes (byte-array attr-len)
              _ (.get buf val-bytes)
               ;; Handle padding
              padding (mod attr-len 4)
              _ (when (and (> padding 0) (< padding 4))
                  (let [pad-len (- 4 padding)]
                    (dotimes [_ pad-len] (.get buf))))]
          (recur (assoc attrs attr-type val-bytes)))
        {:type msg-type :length msg-len :cookie cookie :tx-id tx-id :attributes attrs}))))

(defn make-binding-response
  "Constructs an RFC 5389 STUN Success Response mirroring the request's transaction ID, embedding the resolved `peer-addr` inside an XOR-MAPPED-ADDRESS attribute, and finalizing with MESSAGE-INTEGRITY and FINGERPRINT."
  [password tx-id ^InetSocketAddress peer-addr]
  (let [resp-buf (ByteBuffer/allocate 1024)
        _ (.order resp-buf ByteOrder/BIG_ENDIAN)]
    ;; Header
    (put-unsigned-short resp-buf 0x0101) ;; Success Response
    (put-unsigned-short resp-buf 0)
    (.putInt resp-buf magic-cookie)
    (.put resp-buf tx-id)

    ;; XOR-MAPPED-ADDRESS
    (put-unsigned-short resp-buf ATTR_XOR_MAPPED_ADDRESS)
    (put-unsigned-short resp-buf 8)
    (.put resp-buf (byte 0))
    (.put resp-buf (byte 1))
    (put-unsigned-short resp-buf (bit-xor (.getPort peer-addr) (bit-shift-right magic-cookie 16)))
    (let [addr-bytes (.getAddress (.getAddress peer-addr))
          cookie-bytes (ByteBuffer/allocate 4)
          _ (.putInt cookie-bytes magic-cookie)
          _ (.flip cookie-bytes)
          magic-bytes (.array cookie-bytes)
          xor-addr (byte-array 4)]
      (dotimes [i 4]
        (aset xor-addr i (byte (bit-xor (aget addr-bytes i) (aget magic-bytes i)))))
      (.put resp-buf xor-addr))

    ;; MESSAGE-INTEGRITY
    (let [len-before-mi (- (.position resp-buf) 20)
          len-with-mi (+ len-before-mi 24)]
      (.putShort resp-buf 2 (unchecked-short len-with-mi)))
    (let [len-to-sign (.position resp-buf)
          data-to-sign (byte-array len-to-sign)]
      (.position resp-buf 0)
      (.get resp-buf data-to-sign)
      (.position resp-buf len-to-sign)
      (let [hmac (compute-hmac-sha1 password data-to-sign)]
        (put-unsigned-short resp-buf ATTR_MESSAGE_INTEGRITY)
        (put-unsigned-short resp-buf 20)
        (.put resp-buf hmac)))

    ;; FINGERPRINT
    (let [len-before-fp (- (.position resp-buf) 20)
          total-len (+ len-before-fp 8)]
      (.putShort resp-buf 2 (unchecked-short total-len)))
    (let [len-to-crc (.position resp-buf)
          data-to-crc (byte-array len-to-crc)]
      (.position resp-buf 0)
      (.get resp-buf data-to-crc)
      (.position resp-buf len-to-crc)
      (let [crc (compute-crc32 data-to-crc)]
        (put-unsigned-short resp-buf ATTR_FINGERPRINT)
        (put-unsigned-short resp-buf 4)
        (.putInt resp-buf (unchecked-int crc))))

    (.flip resp-buf)
    resp-buf))

(defn handle-packet
  "Decodes an incoming UDP datagram as STUN bytes. Dispatches ICE Binding Requests to yield a generated `response` if authenticated, and Binding Responses to map newly discovered `candidate` addresses."
  [^ByteBuffer buf ^InetSocketAddress peer-addr connection]
  (try
    (let [start-pos (.position buf)
          _ (.order buf ByteOrder/BIG_ENDIAN)
          msg-type (get-unsigned-short buf)
          msg-len (get-unsigned-short buf)
          cookie (.getInt buf)
          tx-id (byte-array 12)
          _ (.get buf tx-id)
          res (cond
                ;; Binding Request
                (= msg-type 0x0001)
                (if-let [password (:ice-pwd connection)]
                  (if-let [resp (make-binding-response password tx-id peer-addr)]
                    {:response resp}
                    {})
                  {})

                ;; Binding Response
                (= msg-type 0x0101)
                (do
                  ;; Rewind to start-pos to parse the packet fully including header
                  (.position buf start-pos)
                  (let [parsed (parse-packet buf)
                        xor-addr-bytes (get (:attributes parsed) ATTR_XOR_MAPPED_ADDRESS)]
                    (if xor-addr-bytes
                      (let [decoded (decode-xor-mapped-address xor-addr-bytes cookie)
                            candidate-str (str (.getHostAddress ^java.net.InetAddress (:address decoded)) ":" (:port decoded))]
                        {:candidate candidate-str})
                      {})))

                :else
                {})]
      (.position buf (min (.limit buf) (+ start-pos 20 msg-len)))
      res)
    (catch Exception _
      {})))
