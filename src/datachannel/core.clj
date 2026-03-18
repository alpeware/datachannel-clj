(ns datachannel.core
  (:require [datachannel.chunks :as chunks]
            [datachannel.dcep :as dcep]
            [datachannel.dtls :as dtls]
            [datachannel.handlers :as handlers]
            [datachannel.packetize :as packetize]
            [datachannel.reassemble :as reassemble]
            [datachannel.sctp :as sctp]
            [datachannel.stun :as stun])
  (:import [java.nio ByteBuffer]
           [javax.net.ssl SSLEngineResult$HandshakeStatus]
           [java.security SecureRandom]))

(defonce ^:private secure-rand (SecureRandom.))

(def buffer-size
  "Constant used for ByteBuffer sizing representing a reasonably large standard MTU/packet limit."
  65536)

(defn get-buffered-amount
  "Calculates the total byte size of all SCTP data chunks currently enqueued for a given stream ID."
  [state stream-id]
  (let [q (get-in state [:streams stream-id :send-queue] [])]
    (reduce + (map (fn [item]
                     (let [payload (get-in item [:chunk :payload])]
                       (if payload (alength ^bytes payload) 0)))
                   q))))

(defn packetize
  "Delegates to the inner `datachannel.packetize/packetize` logic, processing the connection state to yield outgoing network packets according to congestion limits."
  [state app-events now-ms]
  (packetize/packetize state app-events now-ms))

(defn handle-event
  "Delegates an application event like `:connect` to the state machine routing logic."
  [state event now-ms]
  (handlers/handle-event state event now-ms))

(defn handle-timeout
  "Delegates an expired timer event to the state machine logic."
  [state timer-id now-ms & [engine]]
  (handlers/handle-timeout state timer-id now-ms engine))

(defn reassemble
  "Delegates stream reassembly logic, processing queued inbound chunks to reconstruct application payloads."
  [state app-events]
  (reassemble/reassemble state app-events))

(defn- valid-verification-tag?
  "Checks if the incoming packet has a valid verification tag for the given state."
  [state packet]
  (let [chunks (:chunks packet)
        packet-vtag (:verification-tag packet)
        local-vtag (get state :local-ver-tag 0)
        fallback-vtag (get state :init-tag 0)
        has-init? (some #(= (:type %) :init) chunks)]
    (if has-init?
      (zero? packet-vtag)
      (or (= packet-vtag local-vtag)
          (and (not (zero? fallback-vtag)) (= packet-vtag fallback-vtag))
          (and (zero? local-vtag) (zero? fallback-vtag))))))

(defn handle-sctp-packet
  "Injects a decoded incoming SCTP packet into the unified state machine, advancing through all contained chunks."
  [state packet now-ms]
  (if-not (valid-verification-tag? state packet)
    {:new-state state :network-out [] :app-events []}
    (let [chunks (:chunks packet)
          state-with-rx (-> state
                            (update-in [:metrics :rx-packets] (fnil inc 0))
                            (assoc :remote-port (:src-port packet))
                            (assoc :local-port (:dst-port packet)))
          res (loop [current-state state-with-rx
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
                           (into app-events next-events)))))
          reassembled (reassemble (:new-state res) (:app-events res))]
      (packetize (:new-state reassembled) (:app-events reassembled) now-ms))))

(defn serialize-network-out
  "Encodes items in :network-out into ByteBuffers and stores them in :network-out-bytes"
  [result-map & [^ByteBuffer opt-buf]]
  (let [zero-checksum? (get-in result-map [:new-state :metrics :uses-zero-checksum])
        engine (get-in result-map [:new-state :dtls/engine])
        encoded (mapv (fn [item]
                        (cond
                          (and (map? item) (:packet item)) ; Targeted packet map
                          item
                          (map? item) ; SCTP packet
                          (let [buf (or opt-buf (ByteBuffer/allocateDirect 65536))]
                            (.clear buf)
                            (sctp/encode-packet item buf {:zero-checksum? zero-checksum?})
                            (.flip buf)
                            (if (and engine (or (= (.getHandshakeStatus engine) SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                                                (= (.getHandshakeStatus engine) SSLEngineResult$HandshakeStatus/FINISHED)))
                              (let [net-out (ByteBuffer/allocateDirect 65536)
                                    {:keys [_status bytes]} (dtls/send-app-data engine buf net-out)]
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

(defn handle-receive
  "Primary entry point for multiplexing inbound UDP data, distinguishing STUN, DTLS, and SCTP bytes based on their first byte, running decryption or packet routing."
  [state ^bytes network-bytes now-ms & [remote-addr]]
  (if (zero? (alength network-bytes))
    {:new-state state :network-out [] :app-events []}
    (let [first-byte (bit-and (aget network-bytes 0) 0xFF)
          engine (:dtls/engine state)]
      (cond
        ;; STUN
        (and (>= first-byte 0) (<= first-byte 3))
        (let [buf (ByteBuffer/wrap network-bytes)
              stun-res (stun/handle-packet buf remote-addr state)
              response (:response stun-res)
              candidate (:candidate stun-res)
              new-candidate? (and candidate (not (contains? (:seen-candidates state) candidate)))
              became-connected? (and (not= (:ice-connection-state state) :connected)
                                     (or response candidate))
              new-state (-> state
                            (assoc :last-stun-received now-ms)
                            (cond-> response (assoc :remote-addr remote-addr))
                            (cond-> new-candidate? (update :seen-candidates (fnil conj #{}) candidate))
                            (cond-> new-candidate? (update :remote-candidates (fnil conj []) candidate))
                            (cond-> became-connected? (assoc :ice-connection-state :connected)))
              app-events (cond-> [{:type :stun-packet :payload network-bytes}]
                           new-candidate? (conj {:type :on-ice-candidate :candidate candidate})
                           became-connected? (conj {:type :on-ice-connection-state-change :state :connected}))
              network-out (if response [response] [])]
          {:new-state new-state :network-out network-out :app-events app-events})

        ;; DTLS
        (and (>= first-byte 20) (<= first-byte 63))
        (if-not engine
          {:new-state state :network-out [] :app-events [{:type :dtls-packet :payload network-bytes}]}
          (let [hs-status (.getHandshakeStatus engine)]
            (if (and (or (= hs-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                         (= hs-status SSLEngineResult$HandshakeStatus/FINISHED))
                     (:dtls-verified? state))
              ;; Application data
              (try
                (let [in-buf (ByteBuffer/wrap network-bytes)
                      out-buf (ByteBuffer/allocateDirect 65536)
                      {:keys [_status bytes]} (dtls/receive-app-data engine in-buf out-buf)]
                  (if (and bytes (pos? (alength bytes)))
                    (let [sctp-buf (ByteBuffer/wrap bytes)
                          packet (sctp/decode-packet sctp-buf)]
                      (handle-sctp-packet state packet now-ms))
                    {:new-state state :network-out [] :app-events []}))
                (catch Exception _
                  {:new-state state :network-out [] :app-events []}))
              ;; Handshake
              (try
                (let [in-buf (ByteBuffer/wrap network-bytes)
                      out-buf (ByteBuffer/allocateDirect 65536)
                      {:keys [status packets app-data]} (dtls/handshake engine in-buf out-buf)
                      handshake-finished? (or (= status SSLEngineResult$HandshakeStatus/FINISHED)
                                              (= status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING))]
                  (if (and handshake-finished?
                           (not (:dtls-verified? state)))
                    (if (and (:remote-fingerprint state)
                             (not (dtls/verify-peer-fingerprint engine (:remote-fingerprint state))))
                      (let [state-closed (assoc state :state :closed)
                            abort-chunk {:type :abort
                                         :flags 0
                                         :params []}
                            packet {:src-port (:local-port state 5000)
                                    :dst-port (:remote-port state 5000)
                                    :verification-tag (:remote-ver-tag state 0)
                                    :chunks [abort-chunk]}]
                        {:new-state state-closed
                         :network-out (if (empty? packets) [packet] (conj (vec packets) packet))
                         :app-events [{:type :on-error :message "DTLS Fingerprint Mismatch"} {:type :on-close}]})
                      ;; Verification passed (or listen mode), transition to :connected normally
                      (let [state-verified (assoc state :dtls-verified? true)
                            state-verified (if (and (:client-mode? state-verified) (not (#{:established :cookie-wait :cookie-echoed} (:state state-verified))))
                                             (:new-state (handle-event state-verified {:type :connect} now-ms))
                                             (assoc state-verified :state :connected))
                            base-res {:new-state state-verified :network-out (vec packets) :app-events [{:type :dtls-handshake-progress}]}]
                        (if (and app-data (pos? (alength app-data)))
                          (let [sctp-buf (ByteBuffer/wrap app-data)
                                packet (sctp/decode-packet sctp-buf)
                                sctp-res (handle-sctp-packet (:new-state base-res) packet now-ms)]
                            (-> sctp-res
                                (update :network-out (fn [no] (into (:network-out base-res) no)))
                                (update :app-events (fn [evts] (into (:app-events base-res) evts)))))
                          (if (seq (:pending-control-chunks (:new-state base-res)))
                            (packetize (:new-state base-res) (:app-events base-res) now-ms)
                            base-res))))
                    (if (and app-data (pos? (alength app-data)))
                      (let [sctp-buf (ByteBuffer/wrap app-data)
                            packet (sctp/decode-packet sctp-buf)
                            sctp-res (handle-sctp-packet state packet now-ms)]
                        (-> sctp-res
                            (update :network-out (fn [no] (into (vec packets) no)))
                            (update :app-events conj {:type :dtls-handshake-progress})))
                      {:new-state state :network-out (vec packets) :app-events [{:type :dtls-handshake-progress}]})))
                (catch Exception e
                  (println "DTLS HANDSHAKE EXCEPTION" (.getMessage e))
                  (let [err-msg (if (.getMessage e) (.getMessage e) "DTLS Fingerprint Mismatch")]
                    {:new-state (assoc state :state :closed)
                     :network-out []
                     :app-events [{:type :on-error :message err-msg} {:type :on-close}]}))))))

        ;; SCTP (Default, raw)
        :else
        {:new-state state :network-out [] :app-events []}))))

(defn create-connection
  "Initializes the flattened SCTP/ICE/DTLS pure state map with configured congestion parameters and cryptography contexts."
  [options client-mode?]
  (let [cert-data (or (:cert-data options) (dtls/generate-cert))
        ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
        engine (dtls/create-engine ctx client-mode?)
        local-ver-tag (.nextInt secure-rand 2147483647)]
    {:remote-ver-tag 0
     :local-ver-tag local-ver-tag
     :next-tsn 0
     :ssn 0
     :timers (merge (if (seq (get options :remote-candidates [])) {:stun/check-candidates {:expires-at 0}} {})
                    {:stun/keepalive {:expires-at (+ (System/currentTimeMillis) 1000)}})
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
     :partial-bytes-acked 0
     :buffered-amount-low-threshold (get options :buffered-amount-low-threshold 0)
     :buffered-amount-high-threshold (get options :buffered-amount-high-threshold 1048576)
     :zero-checksum? (:zero-checksum? options)
     :cert-data cert-data
     :ice-ufrag (:ice-ufrag options)
     :ice-pwd (:ice-pwd options)
     :remote-ice-ufrag (:remote-ice-ufrag options)
     :remote-ice-pwd (:remote-ice-pwd options)
     :remote-fingerprint (:remote-fingerprint options)
     :ice-lite? (boolean (:ice-lite? options))
     :ice-gathering-state :new
     :ice-connection-state :new
     :seen-candidates #{}
     :remote-candidates (or (:remote-candidates options) [])
     :data-channels {}
     :client-mode? client-mode?
     :local-outbound-streams (get options :local-outbound-streams 65535)
     :local-inbound-streams (get options :local-inbound-streams 65535)
     :max-queue-size (get options :max-queue-size)
     :dtls/engine engine}))

(declare send-data)

(defn create-data-channel
  "Applies W3C WebRTC interface logic, creating a data channel stream map and dispatching a DCEP OPEN packet for unnegotiated channels."
  [state label options]
  (let [opts (merge {:ordered true
                     :max-packet-life-time nil
                     :max-retransmits nil
                     :protocol ""
                     :negotiated false}
                    options)
        provided-id (:id opts)
        client? (:client-mode? state)
        id (if provided-id
             provided-id
             (loop [potential-id (if client? 0 1)]
               (if (contains? (:data-channels state) potential-id)
                 (recur (+ potential-id 2))
                 potential-id)))
        negotiated? (:negotiated opts)
        channel-state (if negotiated? :open :connecting)
        new-state (assoc-in state [:data-channels id] (assoc opts :label label :state channel-state))]
    (if negotiated?
      {:new-state new-state
       :channel-id id
       :network-out []
       :app-events []}
      (let [open-msg {:type :open
                      :label label
                      :protocol (:protocol opts)
                      :ordered (:ordered opts)
                      :max-retransmits (:max-retransmits opts)
                      :max-packet-life-time (:max-packet-life-time opts)}
            payload (dcep/encode-message open-msg)
            now-ms (System/currentTimeMillis)
            res (send-data new-state payload id :webrtc/dcep now-ms)]
        (assoc res :channel-id id)))))

(defn set-max-message-size
  "Configures the maximum permissible size of a single application message, rejecting sends above this limit."
  [state max-size]
  (assoc state :new-state (assoc state :max-message-size max-size)))

(defn send-data
  "Accepts user application bytes, calculating fragmentation thresholds based on MTU, wrapping the sequence in correctly flagged SCTP DATA chunks, and enqueuing them to a stream's send queue."
  [state ^bytes payload stream-id protocol now-ms]
  (let [len (alength payload)
        max-size (get state :max-message-size 65519)]
    (when (zero? len)
      (throw (ex-info "Cannot send empty message" {:type :empty-payload})))
    (when (> len max-size)
      (throw (ex-info "Cannot send too large message" {:type :too-large})))
    (let [max-queue-size (get state :max-queue-size)
          old-q (get-in state [:streams stream-id :send-queue] [])
          old-buffered (reduce + (map #(if-let [p (:payload (:chunk %))] (alength ^bytes p) 0) old-q))]
      (when (and max-queue-size (> (+ old-buffered len) max-queue-size))
        (throw (ex-info "Queue limit reached" {:type :queue-limit-reached}))))
    (let [_ver-tag (:remote-ver-tag state)
          channel-opts (get-in state [:data-channels stream-id])
          ordered? (if (some? channel-opts) (:ordered channel-opts) true)
          ssn (if ordered? (get-in state [:streams stream-id :next-ssn] 0) 0)
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
                            (recur (+ offset frag-len) (conj frags frag))))))
          old-q (get-in state [:streams stream-id :send-queue] [])
          old-buffered (reduce + (map #(if-let [p (:payload (:chunk %))] (alength ^bytes p) 0) old-q))
          result (loop [remaining-frags fragments
                        current-state state
                        current-tsn (or (:next-tsn state) 0)
                        idx 0]
                   (if (empty? remaining-frags)
                     (let [s1 (-> current-state
                                  (assoc :next-tsn current-tsn)
                                  (cond-> ordered? (assoc-in [:streams stream-id :next-ssn] (inc ssn))))
                           interval (get s1 :heartbeat-interval 30000)
                           s2 (if (and (pos? interval) (contains? (:timers s1) :sctp/t-heartbeat))
                                (assoc-in s1 [:timers :sctp/t-heartbeat] {:expires-at (+ now-ms interval)})
                                s1)
                           s3 (-> s2
                                  (update-in [:metrics :unacked-data] (fnil + 0) (reduce + (map #(+ 16 (alength ^bytes %)) fragments)))
                                  (cond-> is-established?
                                    (update-in [:metrics :tx-bytes] (fnil + 0) len)))
                           s4 (if (and is-established? (nil? (get-in s3 [:timers :sctp/t3-rtx])))
                                (assoc-in s3 [:timers :sctp/t3-rtx] {:expires-at (+ now-ms 1000) :delay 1000})
                                s3)]
                       (if is-established?
                         (packetize s4 [] now-ms)
                         {:new-state s4 :network-out [] :app-events []}))
                     (let [frag (first remaining-frags)
                           total-frags (count fragments)
                           flags (if (= total-frags 1) 3
                                     (cond
                                       (= idx 0) 2
                                       (= idx (dec total-frags)) 1
                                       :else 0))
                           final-flags (if ordered? flags (bit-or flags 4))
                           data-chunk {:type :data
                                       :flags final-flags
                                       :tsn current-tsn
                                       :stream-id stream-id
                                       :seq-num ssn
                                       :protocol protocol
                                       :payload frag}
                           queue-item {:tsn current-tsn
                                       :chunk data-chunk
                                       :sent-at now-ms
                                       :retries 0
                                       :sent? false
                                       :max-packet-life-time (:max-packet-life-time channel-opts)
                                       :max-retransmits (:max-retransmits channel-opts)}
                           new-state (assoc-in current-state [:streams stream-id :send-queue]
                                               (conj (get-in current-state [:streams stream-id :send-queue] []) queue-item))]
                       (recur (rest remaining-frags) new-state (inc current-tsn) (inc idx)))))
          new-q (get-in (:new-state result) [:streams stream-id :send-queue] [])
          new-buffered (reduce + (map #(if-let [p (:payload (:chunk %))] (alength ^bytes p) 0) new-q))
          high-threshold (get state :buffered-amount-high-threshold 1048576)
          app-events (if (and (<= old-buffered high-threshold) (> new-buffered high-threshold))
                       (conj (:app-events result []) {:type :on-buffered-amount-high :stream-id stream-id})
                       (:app-events result []))]
      (assoc result :app-events app-events))))
