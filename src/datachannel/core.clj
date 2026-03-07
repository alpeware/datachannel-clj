(ns datachannel.core
  (:require [datachannel.sctp :as sctp]
            [datachannel.dtls :as dtls]
            [datachannel.stun :as stun])
  (:import [java.nio ByteBuffer]
           [java.net InetSocketAddress StandardSocketOptions]
           [java.nio.channels DatagramChannel Selector SelectionKey]
           [javax.net.ssl SSLEngine SSLEngineResult SSLEngineResult$Status SSLEngineResult$HandshakeStatus]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]
           [java.security SecureRandom]))

(defonce ^:private secure-rand (SecureRandom.))

(def buffer-size 65536)

(defn- make-buffer []
  (ByteBuffer/allocateDirect buffer-size))

(defn- close-channel [ch]
  (when ch
    (try (.close ch) (catch Exception _))))

(defn- close-selector [sel]
  (when sel
    (try (.close sel) (catch Exception _))))

(defn- handle-sctp-packet [packet connection]
  (let [chunks (:chunks packet)
        state (:state connection)]
    (doseq [chunk chunks]
      (case (:type chunk)
        :data
        (let [proto (:protocol chunk)
              tsn (:tsn chunk)]
          ;; Update remote TSN and send SACK
          (swap! state (fn [s]
                         ;; Serial number arithmetic for 32-bit unsigned TSN
                         (if (pos? (unchecked-int (unchecked-subtract (unchecked-int tsn) (unchecked-int (:remote-tsn s)))))
                           (assoc s :remote-tsn tsn)
                           s)))
          (let [sack-packet {:src-port (:dst-port packet)
                             :dst-port (:src-port packet)
                             :verification-tag (:remote-ver-tag @state)
                             :chunks [{:type :sack
                                       :cum-tsn-ack (:remote-tsn @state)
                                       :a-rwnd 100000
                                       :gap-blocks []
                                       :duplicate-tsns []}]}]
             (.offer (:sctp-out connection) sack-packet))

          (cond
            (= proto :webrtc/dcep)
            (let [payload (:payload chunk)
                  msg-type (bit-and (aget ^bytes payload 0) 0xff)]
              (when (= msg-type 3) ;; OPEN
                ;; Send DCEP ACK
                (let [ack-tsn (let [t (:next-tsn @state)]
                                (swap! state update :next-tsn inc)
                                t)
                      ack-ssn (let [s (:ssn @state)]
                                (swap! state update :ssn inc)
                                s)
                      ack-packet {:src-port (:dst-port packet)
                                  :dst-port (:src-port packet)
                                  :verification-tag (:remote-ver-tag @state)
                                  :chunks [{:type :data
                                            :flags 3 ;; B and E bits
                                            :tsn ack-tsn
                                            :stream-id (:stream-id chunk)
                                            :seq-num ack-ssn
                                            :protocol :webrtc/dcep
                                            :payload (byte-array [(byte 2)])}]}]
                   (.offer (:sctp-out connection) ack-packet))))

            :else
            (do
              (when-let [cb @(:on-message connection)]
                (cb (:payload chunk)))
              (when-let [cb @(:on-data connection)]
                (cb {:payload (:payload chunk)
                     :stream-id (:stream-id chunk)
                     :protocol proto})))))

        :init
        (do
          (swap! state assoc :remote-ver-tag (:init-tag chunk)
                             :remote-tsn (dec (:initial-tsn chunk)))
          (let [cookie-bytes (let [b (byte-array 32)] (.nextBytes secure-rand b) b)
                init-ack {:type :init-ack
                          :init-tag (:local-ver-tag @state)
                          :a-rwnd 100000
                          :outbound-streams (:inbound-streams chunk)
                          :inbound-streams (:outbound-streams chunk)
                          :initial-tsn (:next-tsn @state)
                          :params {:cookie cookie-bytes}}
                packet {:src-port (:dst-port packet)
                        :dst-port (:src-port packet)
                        :verification-tag (:init-tag chunk)
                        :chunks [init-ack]}]
            (.offer (:sctp-out connection) packet)))

        :init-ack
        (do
          (swap! state assoc :remote-ver-tag (:init-tag chunk)
                             :remote-tsn (dec (:initial-tsn chunk)))
          (when-let [cookie (get-in chunk [:params :cookie])]
            (let [packet {:src-port (:dst-port packet)
                          :dst-port (:src-port packet)
                          :verification-tag (:init-tag chunk)
                          :chunks [{:type :cookie-echo :cookie cookie}]}]
               (.offer (:sctp-out connection) packet))))

        :cookie-echo
        (do
           (swap! state assoc :state :established)
           (let [packet {:src-port (:dst-port packet)
                         :dst-port (:src-port packet)
                         :verification-tag (:remote-ver-tag @state)
                         :chunks [{:type :cookie-ack}]}]
              (.offer (:sctp-out connection) packet)
              (when-let [cb @(:on-open connection)]
                (cb))))

        :cookie-ack
        (do
           (when (= (:state @state) :shutdown-pending)
             (swap! state assoc :state :shutdown-sent)
             (let [packet {:src-port (:dst-port packet)
                           :dst-port (:src-port packet)
                           :verification-tag (:remote-ver-tag @state)
                           :chunks [{:type :shutdown}]}]
                (.offer (:sctp-out connection) packet)))
           (when-not (= (:state @state) :shutdown-sent)
             (swap! state assoc :state :established))
           (when-let [cb @(:on-open connection)]
             (cb)))

        :heartbeat
        (do
           (let [packet {:src-port (:dst-port packet)
                         :dst-port (:src-port packet)
                         :verification-tag (:verification-tag packet)
                         :chunks [{:type :heartbeat-ack :params (:params chunk)}]}]
              (.offer (:sctp-out connection) packet)))

        :sack nil
        :heartbeat-ack nil
        :shutdown
        (do
           (swap! state assoc :state :shutdown-ack-sent)
           (let [packet {:src-port (:dst-port packet)
                         :dst-port (:src-port packet)
                         :verification-tag (:remote-ver-tag @state)
                         :chunks [{:type :shutdown-ack}]}]
              (.offer (:sctp-out connection) packet)))
        :shutdown-ack
        (do
           (swap! state assoc :state :closed)
           (let [packet {:src-port (:dst-port packet)
                         :dst-port (:src-port packet)
                         :verification-tag (:remote-ver-tag @state)
                         :chunks [{:type :shutdown-complete}]}]
              (.offer (:sctp-out connection) packet)))
        :shutdown-complete
        (do
           (swap! state assoc :state :closed)
           nil)
        :error
        (when-let [cb @(:on-error connection)]
          (cb (:causes chunk)))
        :abort (println "Received SCTP ABORT")
        (let [type-val (:type chunk)]
          (when (number? type-val)
            (let [upper-bits (bit-shift-right (bit-and type-val 0xC0) 6)]
              (cond
                (= upper-bits 1) ;; 01: discard packet and report
                (let [packet {:src-port (:dst-port packet)
                              :dst-port (:src-port packet)
                              :verification-tag (:remote-ver-tag @state)
                              :chunks [{:type :error
                                        :causes [{:cause-code 6 ;; Unrecognized Chunk Type
                                                  :chunk-data (:body chunk)}]}]}]
                  (.offer (:sctp-out connection) packet))
                ;; Other bits (00, 10, 11) will just skip or discard as required, no explicit response needed for now.
                :else nil))))))))


(defn- run-loop [^DatagramChannel channel ^Selector selector ^SSLEngine ssl-engine peer-addr connection & [initial-data]]
  (let [net-in (make-buffer)
        _ (when (and initial-data (.hasRemaining initial-data))
            (.put net-in initial-data)
            (.flip net-in))
        net-out (make-buffer)
        app-in (make-buffer)
        app-out (make-buffer)
        sctp-out (:sctp-out connection)]

    (.register channel selector SelectionKey/OP_READ)

    (if (.getUseClientMode ssl-engine)
      (.beginHandshake ssl-engine))

    (loop [net-in-loop net-in]
      (if (.isOpen channel)
        (do
          (try
            (let [hs-status (.getHandshakeStatus ssl-engine)]
              (if (or (= hs-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
                      (= hs-status SSLEngineResult$HandshakeStatus/FINISHED))
                ;; ESTABLISHED
                (do
                  ;; Incoming
                  (while (.hasRemaining net-in-loop)
                    (let [b (bit-and (.get net-in-loop (.position net-in-loop)) 0xff)]
                      (cond
                        (or (= b 0) (= b 1))
                        (if-let [resp (stun/handle-packet net-in-loop peer-addr connection)]
                          (.send channel resp peer-addr))

                        (and (>= b 20) (<= b 63))
                        (let [res (dtls/receive-app-data ssl-engine net-in-loop app-in)]
                          (when-let [bytes (:bytes res)]
                            (when (> (count bytes) 0)
                              (try (-> (ByteBuffer/wrap bytes) sctp/decode-packet (handle-sctp-packet connection))
                                   (catch Exception e (println "SCTP Decode Error:" e))))))

                        :else
                        (.position net-in-loop (.limit net-in-loop)))))

                  ;; Outgoing
                  (while (let [packet (.poll sctp-out)]
                           (when packet
                             (.clear app-out)
                             (sctp/encode-packet packet app-out)
                             (.flip app-out)
                             (let [res (dtls/send-app-data ssl-engine app-out net-out)]
                               (when-let [bytes (:bytes res)]
                                 (when (> (count bytes) 0)
                                   (.send channel (ByteBuffer/wrap bytes) peer-addr))))
                             packet))))

                ;; HANDSHAKING
                (do
                  (if (and (.hasRemaining net-in-loop)
                           (let [b (bit-and (.get net-in-loop (.position net-in-loop)) 0xff)]
                             (or (= b 0) (= b 1))))
                    (when-let [resp (stun/handle-packet net-in-loop peer-addr connection)]
                      (.send channel resp peer-addr))

                    (when (or (.hasRemaining net-in-loop)
                              (not= hs-status SSLEngineResult$HandshakeStatus/NEED_UNWRAP))
                      (let [res (dtls/handshake ssl-engine net-in-loop net-out)]
                        (doseq [packet (:packets res)]
                          (.send channel (ByteBuffer/wrap packet) peer-addr))
                        (when-let [app-data (:app-data res)]
                          (when (> (count app-data) 0)
                            (try (-> (ByteBuffer/wrap app-data) sctp/decode-packet (handle-sctp-packet connection))
                                 (catch Exception e (println "SCTP Decode Error (Handshake):" e)))))))))))
            (catch Exception e
              (if-not (or (instance? java.nio.channels.ClosedChannelException e)
                          (instance? java.nio.channels.ClosedSelectorException e))
                (println "Error in run-loop processing:" e))))

          (.clear net-in-loop)
          (let [count (.select selector 10)]
            (if (> count 0)
              (let [keys (.selectedKeys selector)]
                (doseq [key keys]
                  (when (.isReadable key)
                    (.receive channel net-in-loop)))
                (.clear keys))))
          (.flip net-in-loop)
          (recur net-in-loop))
        (println "Channel closed.")))))

(defn- create-connection [options client-mode?]
  (let [cert-data (or (:cert-data options) (dtls/generate-cert))
        ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
        engine (dtls/create-engine ctx client-mode?)
        channel (DatagramChannel/open)
        selector (Selector/open)
        sctp-out (LinkedBlockingQueue.)
        local-ver-tag (.nextInt secure-rand 2147483647)
        connection {:sctp-out sctp-out
                    :state (atom {:remote-ver-tag 0
                                  :local-ver-tag local-ver-tag
                                  :next-tsn 0
                                  :ssn 0})
                    :on-message (atom nil)
                    :on-data (atom nil)
                    :on-open (atom nil)
                    :on-error (atom nil)
                    :cert-data cert-data
                    :ice-ufrag (:ice-ufrag options)
                    :ice-pwd (:ice-pwd options)
                    :channel channel
                    :selector selector}]
    {:engine engine
     :channel channel
     :selector selector
     :connection connection
     :local-ver-tag local-ver-tag}))

(defn connect [host port & {:as options}]
  (let [{:keys [engine channel selector connection local-ver-tag]} (create-connection options true)
        peer-addr (InetSocketAddress. host port)]
    (.configureBlocking channel false)
    (.connect channel peer-addr)

    (let [t (Thread.
              (fn []
                (try
                  (run-loop channel selector engine peer-addr connection)
                  (catch Exception e
                    (if-not (or (instance? java.nio.channels.ClosedChannelException e)
                                (instance? java.nio.channels.ClosedSelectorException e))
                      (println "Connection Loop Error:" e))))))]
      (.start t))

    (let [init-chunk {:type :init
                      :init-tag local-ver-tag
                      :a-rwnd 100000
                      :outbound-streams 10
                      :inbound-streams 10
                      :initial-tsn 0
                      :params {}}
          packet {:src-port 5000
                  :dst-port 5000
                  :verification-tag 0
                  :chunks [init-chunk]}]
       (.offer (:sctp-out connection) packet))

    connection))

(defn listen [port & {:as options}]
  (let [{:keys [engine channel selector connection]} (create-connection options (boolean (:dtls-client options)))]
    (.configureBlocking channel false)
    (if-let [host (:host options)]
      (.bind channel (InetSocketAddress. ^String host (int port)))
      (.bind channel (InetSocketAddress. (int port))))

    (let [t (Thread.
              (fn []
                (try
                  (let [temp-buf (make-buffer)
                        peer-addr (do
                                    (.configureBlocking channel true)
                                    (let [addr (.receive channel temp-buf)]
                                      (.configureBlocking channel false)
                                      addr))]
                    (println "Accepted connection from" peer-addr)
                    (.flip temp-buf)
                    (run-loop channel selector engine peer-addr connection temp-buf))
                  (catch Exception e
                    (if-not (or (instance? java.nio.channels.ClosedChannelException e)
                                (instance? java.nio.channels.ClosedSelectorException e))
                      (println "Server Loop Error:" e))))))]
      (.start t))

    connection))

(defn set-max-message-size! [connection max-size]
  (swap! (:state connection) assoc :max-message-size max-size))

(defn send-data [connection ^bytes payload stream-id protocol]
  (let [state (:state connection)
        len (alength payload)
        max-size (get @state :max-message-size 65519)]
    (when (zero? len)
      (throw (ex-info "Cannot send empty message" {:type :empty-payload})))
    (when (> len max-size)
      (throw (ex-info "Cannot send too large message" {:type :too-large}))))
  (let [state (:state connection)
        ver-tag (:remote-ver-tag @state)
        tsn (let [t (:next-tsn @state)]
              (swap! state update :next-tsn inc)
              t)
        ssn (let [s (:ssn @state)]
              (swap! state update :ssn inc)
              s)
        packet {:src-port 5000
                :dst-port 5000
                :verification-tag ver-tag
                :chunks [{:type :data
                          :flags 3 ;; B and E bits
                          :tsn tsn
                          :stream-id stream-id
                          :seq-num ssn
                          :protocol protocol
                          :payload payload}]}]
     (.offer (:sctp-out connection) packet)))

(defn send-msg [connection msg]
  (send-data connection (.getBytes msg "UTF-8") 0 :webrtc/string))

(defn close [connection]
  (close-channel (:channel connection))
  (close-selector (:selector connection)))
