(ns datachannel.core
  (:require [datachannel.sctp :as sctp]
            [datachannel.dtls :as dtls]
            [datachannel.stun :as stun])
  (:import [java.nio ByteBuffer]
           [java.net InetSocketAddress StandardSocketOptions]
           [java.nio.channels DatagramChannel Selector SelectionKey]
           [javax.net.ssl SSLEngine SSLEngineResult SSLEngineResult$Status SSLEngineResult$HandshakeStatus]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def buffer-size 65536)

(defn- make-buffer []
  (ByteBuffer/allocateDirect buffer-size))

(defn- close-channel [ch]
  (when ch
    (try (.close ch) (catch Exception _))))

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
                         (if (> tsn (:remote-tsn s))
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
            (when-let [cb @(:on-message connection)]
               (cb (:payload chunk)))))

        :init
        (do
          (swap! state assoc :remote-ver-tag (:init-tag chunk)
                             :remote-tsn (dec (:initial-tsn chunk)))
          (let [init-ack {:type :init-ack
                          :init-tag (:local-ver-tag @state)
                          :a-rwnd 100000
                          :outbound-streams (:inbound-streams chunk)
                          :inbound-streams (:outbound-streams chunk)
                          :initial-tsn (:next-tsn @state)
                          :params {:cookie (.getBytes (str (System/currentTimeMillis)) "UTF-8")}}
                packet {:src-port (:dst-port packet)
                        :dst-port (:src-port packet)
                        :verification-tag (:init-tag chunk)
                        :chunks [init-ack]}]
            (.offer (:sctp-out connection) packet)))

        :init-ack
        (do
          (swap! state assoc :remote-ver-tag (:init-tag chunk)
                             :remote-tsn (dec (:initial-tsn chunk)))
          (let [cookie (get-in chunk [:params :cookie])
                packet {:src-port (:dst-port packet)
                        :dst-port (:src-port packet)
                        :verification-tag (:init-tag chunk)
                        :chunks [{:type :cookie-echo :cookie cookie}]}]
             (.offer (:sctp-out connection) packet)))

        :cookie-echo
        (do
           (let [packet {:src-port (:dst-port packet)
                         :dst-port (:src-port packet)
                         :verification-tag (:remote-ver-tag @state)
                         :chunks [{:type :cookie-ack}]}]
              (.offer (:sctp-out connection) packet)
              (when-let [cb @(:on-open connection)]
                (cb))))

        :cookie-ack
        (do
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
        :abort (println "Received SCTP ABORT")
        nil))))


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
              (println "Error in run-loop processing:" e)))

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

(defn connect [host port & {:as options}]
  (let [cert-data (or (:cert-data options) (dtls/generate-cert))
        ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
        engine (dtls/create-engine ctx true) ;; Client mode
        channel (DatagramChannel/open)
        selector (Selector/open)
        peer-addr (InetSocketAddress. host port)
        sctp-out (LinkedBlockingQueue.)
        local-ver-tag (rand-int 2147483647)
        connection {:sctp-out sctp-out
                    :state (atom {:remote-ver-tag 0
                                  :local-ver-tag local-ver-tag
                                  :next-tsn 0
                                  :ssn 0})
                    :on-message (atom nil)
                    :on-open (atom nil)
                    :cert-data cert-data
                    :ice-ufrag (:ice-ufrag options)
                    :ice-pwd (:ice-pwd options)
                    :channel channel}]
    (.configureBlocking channel false)
    (.connect channel peer-addr)

    (let [t (Thread.
              (fn []
                (try
                  (run-loop channel selector engine peer-addr connection)
                  (catch Exception e
                    (println "Connection Loop Error:" e)))))]
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
       (.offer sctp-out packet))

    connection))

(defn listen [port & {:as options}]
  (let [cert-data (or (:cert-data options) (dtls/generate-cert))
        ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
        engine (dtls/create-engine ctx (boolean (:dtls-client options)))
        channel (DatagramChannel/open)
        selector (Selector/open)
        sctp-out (LinkedBlockingQueue.)
        local-ver-tag (rand-int 2147483647)
        connection {:sctp-out sctp-out
                    :state (atom {:remote-ver-tag 0
                                  :local-ver-tag local-ver-tag
                                  :next-tsn 0
                                  :ssn 0})
                    :on-message (atom nil)
                    :on-open (atom nil)
                    :cert-data cert-data
                    :ice-ufrag (:ice-ufrag options)
                    :ice-pwd (:ice-pwd options)
                    :channel channel}]
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
                    (println "Server Loop Error:" e)))))]
      (.start t))

    connection))

(defn send-msg [connection msg]
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
                          :stream-id 0
                          :seq-num ssn
                          :protocol :webrtc/string
                          :payload (.getBytes msg "UTF-8")}]}]
     (.offer (:sctp-out connection) packet)))

(defn close [connection]
  (close-channel (:channel connection)))
