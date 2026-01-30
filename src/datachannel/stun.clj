(ns datachannel.stun
  (:import [java.nio ByteBuffer ByteOrder]
           [java.net InetSocketAddress]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util.zip CRC32]))

(def magic-cookie 0x2112A442)

;; Attributes
(def ATTR_USERNAME 0x0006)
(def ATTR_MESSAGE_INTEGRITY 0x0008)
(def ATTR_ERROR_CODE 0x0009)
(def ATTR_UNKNOWN_ATTRIBUTES 0x000A)
(def ATTR_XOR_MAPPED_ADDRESS 0x0020)
(def ATTR_PRIORITY 0x0024)
(def ATTR_USE_CANDIDATE 0x0025)
(def ATTR_FINGERPRINT 0x8028)
(def ATTR_ICE_CONTROLLED 0x8029)
(def ATTR_ICE_CONTROLLING 0x802A)

(defn- put-unsigned-short [^ByteBuffer buf val]
  (.putShort buf (unchecked-short val)))

(defn- put-unsigned-int [^ByteBuffer buf val]
  (.putInt buf (unchecked-int val)))

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

(defn make-binding-request [local-ufrag remote-ufrag remote-pwd]
  (let [buf (ByteBuffer/allocate 1024)
        _ (.order buf ByteOrder/BIG_ENDIAN)
        tx-id (byte-array 12)]
    (.nextBytes (java.util.Random.) tx-id)

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
    (.putInt buf 1853824767) ;; Some priority

    ;; ICE-CONTROLLING (or CONTROLLED)
    ;; Since we are passive/lite, we usually are controlled.
    (put-unsigned-short buf ATTR_ICE_CONTROLLED)
    (put-unsigned-short buf 8)
    (.putLong buf (.nextLong (java.util.Random.)))

    ;; Update Header Length for MI and FINGERPRINT
    (let [len-before-mi (- (.position buf) 20)
          total-len (+ len-before-mi 24 8)]
       (.putShort buf 2 (unchecked-short total-len)))

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

(defn make-simple-binding-request []
  (let [buf (ByteBuffer/allocate 1024)
        _ (.order buf ByteOrder/BIG_ENDIAN)
        tx-id (byte-array 12)]
    (.nextBytes (java.util.Random.) tx-id)

    ;; Header
    (put-unsigned-short buf 0x0001) ;; Binding Request
    (put-unsigned-short buf 0) ;; Length is 0
    (.putInt buf magic-cookie)
    (.put buf tx-id)

    (.flip buf)
    buf))

(defn decode-xor-mapped-address [val-bytes cookie]
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

(defn parse-packet [^ByteBuffer buf]
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

(defn handle-packet [^ByteBuffer buf ^InetSocketAddress peer-addr connection]
  (try
    (let [start-pos (.position buf)
          _ (.order buf ByteOrder/BIG_ENDIAN)
          msg-type (get-unsigned-short buf)
          msg-len (get-unsigned-short buf)
          cookie (.getInt buf)
          tx-id (byte-array 12)]
      (.get buf tx-id)

      ;; Check if it's a Binding Request (0x0001)
      (when (= msg-type 0x0001)
        (println "Received STUN Binding Request from" peer-addr)
        ;; We need ICE password to calculate MESSAGE-INTEGRITY
        (if-let [password (:ice-pwd connection)]
          (let [resp-buf (ByteBuffer/allocate 1024) ;; Should be enough
                _ (.order resp-buf ByteOrder/BIG_ENDIAN)]

            ;; Header
            (put-unsigned-short resp-buf 0x0101) ;; Binding Success Response
            (put-unsigned-short resp-buf 0) ;; Length placeholder
            (.putInt resp-buf magic-cookie)
            (.put resp-buf tx-id)

            ;; Attributes

            ;; XOR-MAPPED-ADDRESS
            (put-unsigned-short resp-buf ATTR_XOR_MAPPED_ADDRESS)
            (put-unsigned-short resp-buf 8) ;; Length
            (.put resp-buf (byte 0)) ;; Reserved
            (.put resp-buf (byte 1)) ;; Family IPv4
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

            ;; Update Header Length to include MI (24) and FINGERPRINT (8)
            ;; Current Body Length = Pos - 20
            (let [len-before-mi (- (.position resp-buf) 20)
                  total-len (+ len-before-mi 24 8)]
               (.putShort resp-buf 2 (unchecked-short total-len)))

            ;; Compute HMAC over [0 .. current_pos]
            (let [len-to-sign (.position resp-buf)
                  data-to-sign (byte-array len-to-sign)]
               (.position resp-buf 0)
               (.get resp-buf data-to-sign)
               (.position resp-buf len-to-sign) ;; Restore pos

               (let [hmac (compute-hmac-sha1 password data-to-sign)]
                  (put-unsigned-short resp-buf ATTR_MESSAGE_INTEGRITY)
                  (put-unsigned-short resp-buf 20)
                  (.put resp-buf hmac)))

            ;; Compute CRC32 over [0 .. current_pos]
            (let [len-to-crc (.position resp-buf)
                  data-to-crc (byte-array len-to-crc)]
               (.position resp-buf 0)
               (.get resp-buf data-to-crc)
               (.position resp-buf len-to-crc) ;; Restore pos

               (let [crc (compute-crc32 data-to-crc)]
                  (put-unsigned-short resp-buf ATTR_FINGERPRINT)
                  (put-unsigned-short resp-buf 4)
                  (.putInt resp-buf (unchecked-int crc))))

            (.flip resp-buf)
            resp-buf)
          (do
            (println "STUN Binding Request received but no ice-pwd configured.")
            nil))))

    (catch Exception e
      (println "Error handling STUN packet:" e)
      nil)))
