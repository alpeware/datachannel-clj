(ns datachannel.sctp
  (:require [clojure.set :as set]
            [clojure.string :as string])
  (:import
   [java.nio ByteBuffer ByteOrder]
   [java.util.zip CRC32C]))

;; Chunk types
(def chunk-types
  {:data 0
   :init 1
   :init-ack 2
   :sack 3
   :heartbeat 4
   :heartbeat-ack 5
   :abort 6
   :shutdown 7
   :shutdown-ack 8
   :error 9
   :cookie-echo 10
   :cookie-ack 11
   :ecne 12
   :cwr 13
   :shutdown-complete 14
   :auth 15
   :re-config 130
   :asconf-ack 0x80
   :asconf 0xc1
   :forward-tsn 192})

(def chunk-type-map (set/map-invert chunk-types))

;; Parameters
(def parameters
  {:heartbeat 1
   :ipv4 5
   :ipv6 6
   :cookie 7
   :cookie-ttl 9
   :hostname 11
   :address-family 12
   :forward-tsn 49152
   :extensions 0x8008
   :random 0x8002
   :hmac-algo 0x8004
   :chunks 0x8003})

(def parameter-map (set/map-invert parameters))

;; Protocols
(def protocols
  {:webrtc/dcep 50
   :webrtc/string 51
   :webrtc/binary 53
   :webrtc/empty-string 56
   :webrtc/empty-binary 57})

(def protocol-map (set/map-invert protocols))

;; Data Channel Message Types (DCEP)
(def dcep-types
  {:ack 2
   :open 3})

(def dcep-type-map (set/map-invert dcep-types))

;; Channel Types
(def channel-types
  {:reliable 0x00
   :reliable-unordered 0x80
   :partial-reliable 0x01
   :partial-reliable-unordered 0x81
   :partial-reliable-timed 0x02
   :partial-reliable-timed-unordered 0x82})

(def channel-type-map (set/map-invert channel-types))

(defn- pad [len]
  (let [rem (mod len 4)]
    (if (zero? rem) 0 (- 4 rem))))

(defn- get-unsigned-int [^ByteBuffer buf]
  (bit-and (.getInt buf) 0xffffffff))

(defn- get-unsigned-short [^ByteBuffer buf]
  (bit-and (.getShort buf) 0xffff))

(defn- get-unsigned-byte [^ByteBuffer buf]
  (bit-and (.get buf) 0xff))

(defn decode-params [^ByteBuffer buf total-length]
  (let [end (+ (.position buf) total-length)]
    (loop [params {}]
      (if (>= (.position buf) end)
        params
        (let [type-code (get-unsigned-short buf)
              len (get-unsigned-short buf)
              val-len (- len 4)
              type-key (get parameter-map type-code type-code)
              val-bytes (byte-array val-len)]
          (.get buf val-bytes)
          ;; Skip padding
          (let [padding (pad len)]
             (if (< (.remaining buf) padding)
               (do ;; Error or incomplete buffer, just skip what we can or return
                 params)
               (do
                 (.position buf (+ (.position buf) padding))
                 (recur (assoc params type-key val-bytes))))))))))

(defn encode-params [^ByteBuffer buf params]
  (doseq [[k v] params]
    (let [type-code (if (keyword? k) (get parameters k) k)
          v-bytes (if (bytes? v) v (byte-array 0))
          len (+ 4 (alength v-bytes))
          padding (pad len)]
      (.putShort buf (unchecked-short type-code))
      (.putShort buf (unchecked-short len))
      (.put buf v-bytes)
      (dotimes [_ padding] (.put buf (byte 0))))))

(defn decode-chunk [^ByteBuffer buf]
  (let [type-code (get-unsigned-byte buf)
        flags (get-unsigned-byte buf)
        len (get-unsigned-short buf)
        type-key (get chunk-type-map type-code type-code)
        val-len (- len 4)]
    (cond
      (or (< len 4) (> val-len (.remaining buf)))
      nil ;; Invalid length

      :else
      (let [chunk-start (.position buf)
            chunk-data {:type type-key
                        :flags flags
                        :length len}
            parsed-data
            (case type-key
              :data
              (let [tsn (get-unsigned-int buf)
                    stream-id (get-unsigned-short buf)
                    seq-num (get-unsigned-short buf)
                    proto-id (get-unsigned-int buf)
                    payload-len (- val-len 12)
                    payload (byte-array payload-len)]
                (.get buf payload)
                (merge chunk-data
                       {:tsn tsn
                        :stream-id stream-id
                        :seq-num seq-num
                        :protocol (get protocol-map proto-id proto-id)
                        :payload payload
                        :unordered (bit-test flags 2)
                        :beginning (bit-test flags 1)
                        :ending (bit-test flags 0)}))

              :init
              (let [init-tag (get-unsigned-int buf)
                    a-rwnd (get-unsigned-int buf)
                    outbound-streams (get-unsigned-short buf)
                    inbound-streams (get-unsigned-short buf)
                    initial-tsn (get-unsigned-int buf)
                    params-len (- val-len 20)]
                 (merge chunk-data
                        {:init-tag init-tag
                         :a-rwnd a-rwnd
                         :outbound-streams outbound-streams
                         :inbound-streams inbound-streams
                         :initial-tsn initial-tsn
                         :params (decode-params buf params-len)}))

              :init-ack
              (let [init-tag (get-unsigned-int buf)
                    a-rwnd (get-unsigned-int buf)
                    outbound-streams (get-unsigned-short buf)
                    inbound-streams (get-unsigned-short buf)
                    initial-tsn (get-unsigned-int buf)
                    params-len (- val-len 20)]
                 (merge chunk-data
                        {:init-tag init-tag
                         :a-rwnd a-rwnd
                         :outbound-streams outbound-streams
                         :inbound-streams inbound-streams
                         :initial-tsn initial-tsn
                         :params (decode-params buf params-len)}))

              :cookie-echo
              (let [cookie (byte-array val-len)]
                (.get buf cookie)
                (merge chunk-data {:cookie cookie}))

              :sack
              (let [cum-tsn-ack (get-unsigned-int buf)
                    a-rwnd (get-unsigned-int buf)
                    num-gap-ack-blocks (get-unsigned-short buf)
                    num-duplicate-tsns (get-unsigned-short buf)
                    gap-blocks (vec (repeatedly num-gap-ack-blocks
                                              #(vector (get-unsigned-short buf) (get-unsigned-short buf))))
                    duplicate-tsns (vec (repeatedly num-duplicate-tsns #(get-unsigned-int buf)))]
                (merge chunk-data
                       {:cum-tsn-ack cum-tsn-ack
                        :a-rwnd a-rwnd
                        :gap-blocks gap-blocks
                        :duplicate-tsns duplicate-tsns}))

              :heartbeat
              (let [params (decode-params buf val-len)]
                 (merge chunk-data {:params params}))

              :heartbeat-ack
              (let [params (decode-params buf val-len)]
                 (merge chunk-data {:params params}))

              :abort
              (do
                 ;; Just skip for now
                 (.position buf (+ chunk-start val-len))
                 chunk-data)

              :cookie-ack
              chunk-data

              :shutdown
              chunk-data

              :shutdown-ack
              chunk-data

              ;; Default: just consume the body bytes
              (let [body (byte-array val-len)]
                (.get buf body)
                (merge chunk-data {:body body})))]

        ;; Handle padding
        (let [padding (pad len)]
          (if (<= (+ (.position buf) padding) (.limit buf))
             (.position buf (+ (.position buf) padding))
             (.position buf (.limit buf))))

        parsed-data))))

(defn decode-packet [^ByteBuffer buf]
  (let [src-port (get-unsigned-short buf)
        dst-port (get-unsigned-short buf)
        ver-tag (get-unsigned-int buf)
        checksum (get-unsigned-int buf)]
    {:src-port src-port
     :dst-port dst-port
     :verification-tag ver-tag
     :checksum checksum
     :chunks (loop [chunks []]
               (if (.hasRemaining buf)
                 (if-let [chunk (decode-chunk buf)]
                   (recur (conj chunks chunk))
                   chunks)
                 chunks))}))

(defn encode-chunk [^ByteBuffer buf chunk]
  (let [start-pos (.position buf)
        type-key (:type chunk)
        type-code (if (keyword? type-key) (get chunk-types type-key) type-key)
        flags (:flags chunk 0)]
    (.put buf (byte type-code))
    (.put buf (byte flags))
    (.putShort buf 0) ;; Length placeholder

    (case type-key
      :data
      (do
        (.putInt buf (unchecked-int (:tsn chunk)))
        (.putShort buf (unchecked-short (:stream-id chunk)))
        (.putShort buf (unchecked-short (:seq-num chunk)))
        (.putInt buf (unchecked-int (get protocols (:protocol chunk) (:protocol chunk))))
        (.put buf ^bytes (:payload chunk)))

      :init
      (do
        (.putInt buf (unchecked-int (:init-tag chunk)))
        (.putInt buf (unchecked-int (:a-rwnd chunk)))
        (.putShort buf (unchecked-short (:outbound-streams chunk)))
        (.putShort buf (unchecked-short (:inbound-streams chunk)))
        (.putInt buf (unchecked-int (:initial-tsn chunk)))
        (encode-params buf (:params chunk)))

      :init-ack
      (do
        (.putInt buf (unchecked-int (:init-tag chunk)))
        (.putInt buf (unchecked-int (:a-rwnd chunk)))
        (.putShort buf (unchecked-short (:outbound-streams chunk)))
        (.putShort buf (unchecked-short (:inbound-streams chunk)))
        (.putInt buf (unchecked-int (:initial-tsn chunk)))
        (encode-params buf (:params chunk)))

      :cookie-echo
      (.put buf ^bytes (:cookie chunk))

      :sack
      (do
        (.putInt buf (unchecked-int (:cum-tsn-ack chunk)))
        (.putInt buf (unchecked-int (:a-rwnd chunk)))
        (.putShort buf (unchecked-short (count (:gap-blocks chunk))))
        (.putShort buf (unchecked-short (count (:duplicate-tsns chunk))))
        (doseq [[start end] (:gap-blocks chunk)]
          (.putShort buf (unchecked-short start))
          (.putShort buf (unchecked-short end)))
        (doseq [dup (:duplicate-tsns chunk)]
          (.putInt buf (unchecked-int dup))))

      :heartbeat
      (encode-params buf (:params chunk))

      :heartbeat-ack
      (encode-params buf (:params chunk))

      ;; Default
      (when (:body chunk)
        (.put buf ^bytes (:body chunk))))

    (let [end-pos (.position buf)
          len (- end-pos start-pos)
          padding (pad len)]
      (.putShort buf (+ start-pos 2) (unchecked-short len))
      (dotimes [_ padding] (.put buf (byte 0))))))

(defn update-checksum [^ByteBuffer buf]
  (let [crc (CRC32C.)
        pos (.position buf)]
    (.flip buf)
    ;; CRC calculation logic:
    ;; The checksum field is at offset 8 (0-indexed). It is filled with 0s for calculation.
    (.putInt buf 8 0)
    (.update crc buf)
    (let [checksum (.getValue crc)]
      (.putInt buf 8 (unchecked-int checksum))
      (.position buf pos)
      buf)))

(defn encode-packet [packet ^ByteBuffer buf]
  (.putShort buf (unchecked-short (:src-port packet)))
  (.putShort buf (unchecked-short (:dst-port packet)))
  (.putInt buf (unchecked-int (:verification-tag packet)))
  (.putInt buf 0) ;; Checksum placeholder
  (doseq [chunk (:chunks packet)]
    (encode-chunk buf chunk))
  (update-checksum buf))
