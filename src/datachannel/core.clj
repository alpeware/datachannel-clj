(ns datachannel.core
  (:require [datachannel.sctp :as sctp]
            [datachannel.dtls :as dtls]
            [datachannel.stun :as stun]
            [datachannel.packetize :as packetize]
            [datachannel.reassemble :as reassemble]
            [datachannel.handlers :as handlers]
            [datachannel.chunks :as chunks])
  (:import [java.nio ByteBuffer]
           [java.net InetSocketAddress StandardSocketOptions]
                      [javax.net.ssl SSLEngine SSLEngineResult SSLEngineResult$Status SSLEngineResult$HandshakeStatus]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.security SecureRandom]))

(defonce ^:private secure-rand (SecureRandom.))

(def buffer-size 65536)

(defn- make-buffer []
  (ByteBuffer/allocateDirect buffer-size))

(defn packetize [state app-events]
  (packetize/packetize state app-events))

(defn handle-event [state event now-ms]
  (handlers/handle-event state event now-ms))

(defn handle-timeout [state timer-id now-ms & [engine]]
  (handlers/handle-timeout state timer-id now-ms engine))


(defn reassemble [state app-events]
  (reassemble/reassemble state app-events))

(defn handle-sctp-packet [state packet now-ms]
  (let [chunks (:chunks packet)
        state-with-rx (-> state
                          (update-in [:metrics :rx-packets] (fnil inc 0))
                          (assoc :remote-port (:src-port packet))
                          (assoc :local-port (:dst-port packet)))]
    (let [res (loop [current-state state-with-rx
                     remaining-chunks chunks
                     app-events []]
                (if (empty? remaining-chunks)
                  {:new-state current-state
                   :app-events app-events}
                  (let [chunk (first remaining-chunks)
                        {:keys [next-state next-events]}
                        (chunks/process-chunk current-state chunk packet now-ms)]
                    (recur next-state
                           (rest remaining-chunks)
                           (into app-events next-events)))))]
      (let [reassembled (reassemble (:new-state res) (:app-events res))]
        (packetize (:new-state reassembled) (:app-events reassembled))))))

(defn serialize-network-out
  "Encodes items in :network-out into ByteBuffers and stores them in :network-out-bytes"
  [result-map & [^ByteBuffer opt-buf ^SSLEngine engine]]
  (let [zero-checksum? (get-in result-map [:new-state :metrics :uses-zero-checksum])
        encoded (mapv (fn [item]
                        (cond
                          (map? item) ; SCTP packet
                          (let [buf (or opt-buf (ByteBuffer/allocateDirect 65536))]
                            (.clear buf)
                            (sctp/encode-packet item buf {:zero-checksum? zero-checksum?})
                            (.flip buf)
                            (if (and engine (or (= (.getHandshakeStatus engine) SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                                                (= (.getHandshakeStatus engine) SSLEngineResult$HandshakeStatus/FINISHED)))
                              (let [net-out (ByteBuffer/allocateDirect 65536)
                                    {:keys [status bytes]} (dtls/send-app-data engine buf net-out)]
                                (ByteBuffer/wrap bytes))
                              buf))
                          (instance? ByteBuffer item) ; Already encoded (STUN/DTLS)
                          item
                          (bytes? item) ; byte-array
                          (ByteBuffer/wrap item)
                          :else
                          (throw (ex-info "Unknown item type in network-out" {:item item}))))
                      (:network-out result-map []))]
    (assoc result-map :network-out-bytes encoded)))

(defn handle-receive [state ^bytes network-bytes now-ms & [remote-addr ^SSLEngine engine]]
  (if (zero? (alength network-bytes))
    {:new-state state :network-out [] :app-events []}
    (let [first-byte (bit-and (aget network-bytes 0) 0xFF)]
      (cond
        ;; STUN
        (and (>= first-byte 0) (<= first-byte 3))
        (let [buf (ByteBuffer/wrap network-bytes)
              stun-res (stun/handle-packet buf remote-addr state)]
          (if (instance? ByteBuffer stun-res)
            {:new-state state :network-out [stun-res] :app-events [{:type :stun-packet :payload network-bytes}]}
            {:new-state state :network-out [] :app-events [{:type :stun-packet :payload network-bytes}]}))

        ;; DTLS
        (and (>= first-byte 20) (<= first-byte 63))
        (if-not engine
          {:new-state state :network-out [] :app-events [{:type :dtls-packet :payload network-bytes}]}
          (let [hs-status (.getHandshakeStatus engine)]
            (if (or (= hs-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                    (= hs-status SSLEngineResult$HandshakeStatus/FINISHED))
              ;; Application data
              (let [in-buf (ByteBuffer/wrap network-bytes)
                    out-buf (ByteBuffer/allocateDirect 65536)
                    {:keys [status bytes]} (dtls/receive-app-data engine in-buf out-buf)]
                (if (and bytes (pos? (alength bytes)))
                  (let [sctp-buf (ByteBuffer/wrap bytes)
                        packet (sctp/decode-packet sctp-buf)]
                    (handle-sctp-packet state packet now-ms))
                  {:new-state state :network-out [] :app-events []}))
              ;; Handshake
              (let [in-buf (ByteBuffer/wrap network-bytes)
                    out-buf (ByteBuffer/allocateDirect 65536)
                    {:keys [status packets app-data]} (dtls/handshake engine in-buf out-buf)]
                (if (and app-data (pos? (alength app-data)))
                  (let [sctp-buf (ByteBuffer/wrap app-data)
                        packet (sctp/decode-packet sctp-buf)
                        sctp-res (handle-sctp-packet state packet now-ms)]
                    (-> sctp-res
                        (update :network-out (fn [no] (into (vec packets) no)))
                        (update :app-events conj {:type :dtls-handshake-progress})))
                  {:new-state state :network-out (vec packets) :app-events [{:type :dtls-handshake-progress}]})))))

        ;; SCTP (Default, raw)
        :else
        (let [buf (ByteBuffer/wrap network-bytes)
              packet (sctp/decode-packet buf)]
          (handle-sctp-packet state packet now-ms))))))

(defn create-connection [options client-mode?]
  (let [cert-data (or (:cert-data options) (dtls/generate-cert))
        ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
        engine (dtls/create-engine ctx client-mode?)
        local-ver-tag (.nextInt secure-rand 2147483647)
        connection {:state (atom {:remote-ver-tag 0
                                  :local-ver-tag local-ver-tag
                                  :next-tsn 0
                                  :ssn 0
                                  :timers {}
                                  :heartbeat-interval (get options :heartbeat-interval 30000)
                                  :heartbeat-error-count 0
                                  :rto-initial (get options :rto-initial 1000)
                                  :max-retransmissions (get options :max-retransmissions 10)
                                  :mtu (get options :mtu 1200)
                                  :streams {}
                                  :pending-control-chunks []
                                  :metrics {:tx-packets 0
                                            :rx-packets 0
                                            :tx-bytes   0
                                            :rx-bytes   0
                                            :retransmissions 0
                                            :unacked-data 0
                                            :uses-zero-checksum (boolean (:zero-checksum? options))}
                                  :cwnd (let [mtu (get options :mtu 1200)]
                                          (min (* 4 mtu) (max (* 2 mtu) 4380)))
                                  :ssthresh 65535 ; Initial peer rwnd or 65535
                                  :flight-size 0
                                  :partial-bytes-acked 0})
                    :zero-checksum? (:zero-checksum? options)
                    :cert-data cert-data
                    :ice-ufrag (:ice-ufrag options)
                    :ice-pwd (:ice-pwd options)
                    }]
    {:engine engine
     :connection connection
     :local-ver-tag local-ver-tag}))

(defn set-max-message-size! [connection max-size]
  (swap! (:state connection) assoc :max-message-size max-size))

(defn send-data [state ^bytes payload stream-id protocol now-ms]
  (let [len (alength payload)
        max-size (get state :max-message-size 65519)]
    (when (zero? len)
      (throw (ex-info "Cannot send empty message" {:type :empty-payload})))
    (when (> len max-size)
      (throw (ex-info "Cannot send too large message" {:type :too-large})))
    (let [ver-tag (:remote-ver-tag state)
          ssn (get-in state [:streams stream-id :next-ssn] 0)
          mtu (get state :mtu 1200)
          max-payload-per-chunk (- mtu 16)
          is-established? (= (:state state) :established)
          fragments (if (<= len max-payload-per-chunk)
                      [payload]
                      (loop [offset 0
                             frags []]
                        (if (>= offset len)
                          frags
                          (let [frag-len (min max-payload-per-chunk (- len offset))
                                frag (java.util.Arrays/copyOfRange payload offset (+ offset frag-len))]
                            (recur (+ offset frag-len) (conj frags frag))))))]
      (loop [remaining-frags fragments
             current-state state
             current-tsn (or (:next-tsn state) 0)
             idx 0]
        (if (empty? remaining-frags)
          (let [s1 (-> current-state
                       (assoc :next-tsn current-tsn)
                       (assoc-in [:streams stream-id :next-ssn] (inc ssn)))
                interval (get s1 :heartbeat-interval 30000)
                s2 (if (and (pos? interval) (contains? (:timers s1) :sctp/t-heartbeat))
                     (assoc-in s1 [:timers :sctp/t-heartbeat] {:expires-at (+ now-ms interval)})
                     s1)
                s3 (-> s2
                       (update-in [:metrics :unacked-data] (fnil + 0) (count fragments))
                       (cond-> is-established?
                         (-> (update-in [:metrics :tx-packets] (fnil + 0) (count fragments))
                             (update-in [:metrics :tx-bytes] (fnil + 0) len))))
                s4 (if (and is-established? (nil? (get-in s3 [:timers :sctp/t3-rtx])))
                     (assoc-in s3 [:timers :sctp/t3-rtx] {:expires-at (+ now-ms 1000) :delay 1000})
                     s3)]
            (if is-established?
              (loop [current-state s4
                     all-pkts []
                     passes 0]
                (if (> passes 100)
                  {:new-state current-state :network-out all-pkts :app-events []}
                  (let [pack-res (packetize current-state [])
                        pkts (:network-out pack-res)]
                    (if (empty? pkts)
                      {:new-state current-state :network-out all-pkts :app-events []}
                      (recur (:new-state pack-res) (into all-pkts pkts) (inc passes))))))
              {:new-state s4 :network-out [] :app-events []}))
          (let [frag (first remaining-frags)
                total-frags (count fragments)
                flags (if (= total-frags 1) 3
                          (cond
                            (= idx 0) 2
                            (= idx (dec total-frags)) 1
                            :else 0))
                data-chunk {:type :data
                            :flags flags
                            :tsn current-tsn
                            :stream-id stream-id
                            :seq-num ssn
                            :protocol protocol
                            :payload frag}
                queue-item {:tsn current-tsn :chunk data-chunk :sent-at now-ms :retries 0 :sent? false}
                new-state (assoc-in current-state [:streams stream-id :send-queue]
                                    (conj (get-in current-state [:streams stream-id :send-queue] []) queue-item))]
            (recur (rest remaining-frags) new-state (inc current-tsn) (inc idx))))))))
