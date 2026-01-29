(ns datachannel.core
  (:require [datachannel.sctp :as sctp]
            [datachannel.dtls :as dtls])
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
  #_(println "Handling SCTP packet:" packet)
  (let [chunks (:chunks packet)
        state (:state connection)]
    (doseq [chunk chunks]
      (case (:type chunk)
        :data
        (do
          #_(println "Received DATA chunk")
          ;; Send SACK? (Not implemented yet, but we should ack eventually)
          ;; Deliver data
          (when-let [cb @(:on-message connection)]
             (cb (:payload chunk))))

        :init
        (do
          #_(println "Received INIT")
          ;; Update remote-ver-tag from INIT chunk
          (swap! state assoc :remote-ver-tag (:init-tag chunk))

          ;; Respond with INIT_ACK
          (let [init-ack {:type :init-ack
                          :init-tag (:local-ver-tag @state)
                          :a-rwnd 100000
                          :outbound-streams (:inbound-streams chunk)
                          :inbound-streams (:outbound-streams chunk)
                          :initial-tsn (rand-int 2147483647)
                          :params {:cookie (.getBytes (str (System/currentTimeMillis)) "UTF-8")}}
                packet {:src-port (:dst-port packet)
                        :dst-port (:src-port packet)
                        :verification-tag (:init-tag chunk)
                        :chunks [init-ack]}]
            (.offer (:sctp-out connection) packet)))

        :init-ack
        (do
          #_(println "Received INIT_ACK")
          ;; Update remote-ver-tag from INIT_ACK chunk
          (swap! state assoc :remote-ver-tag (:init-tag chunk))

          ;; Respond with COOKIE_ECHO
          (let [cookie (get-in chunk [:params :cookie])
                packet {:src-port (:dst-port packet)
                        :dst-port (:src-port packet)
                        :verification-tag (:init-tag chunk)
                        :chunks [{:type :cookie-echo :cookie cookie}]}]
             (.offer (:sctp-out connection) packet)))

        :cookie-echo
        (do
           #_(println "Received COOKIE_ECHO")
           ;; Respond with COOKIE_ACK
           ;; Use remote-ver-tag for the response packet header
           (let [packet {:src-port (:dst-port packet)
                         :dst-port (:src-port packet)
                         :verification-tag (:remote-ver-tag @state)
                         :chunks [{:type :cookie-ack}]}]
              (.offer (:sctp-out connection) packet)
              (when-let [cb @(:on-open connection)]
                (cb))))

        :cookie-ack
        (do
           #_(println "Received COOKIE_ACK")
           (when-let [cb @(:on-open connection)]
             (cb)))

        :heartbeat
        (do
           #_(println "Received HEARTBEAT")
           ;; Respond with HEARTBEAT_ACK
           (let [packet {:src-port (:dst-port packet)
                         :dst-port (:src-port packet)
                         :verification-tag (:verification-tag packet)
                         :chunks [{:type :heartbeat-ack :params (:params chunk)}]}]
              (.offer (:sctp-out connection) packet)))

        ;; Ignore others for now
        #_(println "Ignored chunk type:" (:type chunk))))))


(defn- run-loop [channel selector ssl-engine peer-addr connection & [initial-data]]
  (let [net-in (make-buffer)
        _ (when (and initial-data (.hasRemaining initial-data))
            (.put net-in initial-data))
        net-out (make-buffer)
        app-in (make-buffer)  ;; Decrypted DTLS (Incoming SCTP)
        app-out (make-buffer) ;; To be Encrypted DTLS (Outgoing SCTP)
        sctp-out (:sctp-out connection)
        pending-app-out (atom nil)]

    (.register channel selector SelectionKey/OP_READ)

    (if (.getUseClientMode ssl-engine)
      (.beginHandshake ssl-engine))

    (loop []
      (if (.isOpen channel)
        (do
          #_(let [hs-status (.getHandshakeStatus ssl-engine)]
            (when (not= hs-status SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING)
               (println "Loop HS Status:" hs-status)))

          (let [count (.select selector 10)] ;; Faster polling

            ;; Check for outgoing SCTP packets to wrap
            ;; Only try to send app data if handshake is finished
            (when (contains? #{SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING SSLEngineResult$HandshakeStatus/FINISHED} (.getHandshakeStatus ssl-engine))
              (when (or @pending-app-out (not (.isEmpty sctp-out)))
                 (let [packet (or @pending-app-out (.poll sctp-out))]
                   (when packet
                     (reset! pending-app-out nil)
                     (.clear app-out)
                     (sctp/encode-packet packet app-out)
                     (.flip app-out)

                     (let [res (.wrap ssl-engine app-out net-out)]
                       #_(println "Wrap App Data Result:" res)
                       (when (= (.getStatus res) SSLEngineResult$Status/OK)
                         (.flip net-out)
                         (if (.hasRemaining net-out)
                            (do
                              (.send channel net-out peer-addr)
                              #_(println "Sent Encrypted SCTP packet bytes:" (.limit net-out)))
                            #_(println "Wrap produced 0 bytes"))
                         (.compact net-out))

                       (if (> (.bytesConsumed res) 0)
                         (do
                           #_(println "Consumed SCTP packet, type:" (map :type (:chunks packet)))
                           (reset! pending-app-out nil))
                         (do
                           #_(println "SCTP packet not consumed, requeuing. HS Status:" (.getHandshakeStatus ssl-engine))
                           (reset! pending-app-out packet))))))))

            ;; DTLS Handshake Wrap (for pure handshake messages)
            (when (= (.getHandshakeStatus ssl-engine) SSLEngineResult$HandshakeStatus/NEED_WRAP)
              (.clear net-out)
              ;; Use empty app-in for handshake wrap
              (.clear app-in)
              (.flip app-in)
              (let [res (.wrap ssl-engine app-in net-out)]
                 (.flip net-out)
                 (if (.hasRemaining net-out)
                   (do
                     (.send channel net-out peer-addr)
                     #_(println "Sent DTLS Handshake packet bytes:" (.limit net-out)))
                   #_(println "Warning: NEED_WRAP produced 0 bytes. Status:" (.getStatus res) "HS Status:" (.getHandshakeStatus res)))
                 (.compact net-out)))

            ;; DTLS Task
            (loop []
              (when (= (.getHandshakeStatus ssl-engine) SSLEngineResult$HandshakeStatus/NEED_TASK)
                 (let [task (.getDelegatedTask ssl-engine)]
                   (when task
                     (.run task)
                     (recur)))))

            (if (> count 0)
              (let [keys (.selectedKeys selector)]
                (doseq [key keys]
                  (when (.isReadable key)
                    (.clear net-in)
                    (let [addr (.receive channel net-in)]
                      #_(println "Received UDP packet from" addr "bytes:" (.position net-in))
                      (.flip net-in)

                      ;; Loop to consume all records in the packet
                      (loop [retry false]
                        (when (or (.hasRemaining net-in) retry)
                          (let [res (.unwrap ssl-engine net-in app-in)
                                status (.getStatus res)
                                hs-status (.getHandshakeStatus res)]
                            #_(println "Unwrap result:" res)
                            #_(when (or (> (.bytesConsumed res) 0) (> (.bytesProduced res) 0))
                               (println "DTLS Unwrap consumed" (.bytesConsumed res) "produced" (.bytesProduced res) "HS Status:" hs-status))

                            (let [should-retry (= hs-status SSLEngineResult$HandshakeStatus/NEED_UNWRAP_AGAIN)]
                              (condp = status
                                SSLEngineResult$Status/OK
                                (do
                                  (.flip app-in)
                                  (when (.hasRemaining app-in)
                                    ;; We have decrypted data! Parse SCTP.
                                    (try
                                      (let [packet (sctp/decode-packet app-in)]
                                        (handle-sctp-packet packet connection))
                                      (catch Exception e
                                        (println "SCTP Decode Error:" e))))
                                  (.compact app-in)
                                  (recur should-retry))

                                SSLEngineResult$Status/BUFFER_UNDERFLOW
                                (do
                                  ;; Need more data
                                  (.compact net-in))

                                (do
                                  (println "DTLS Unwrap Status:" status)
                                  (.compact net-in))))))))))
                (.clear keys))))
          (recur))
        (println "Channel closed.")))))

(defn connect [host port]
  (let [cert-data (dtls/generate-cert)
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
                    :on-open (atom nil)}]
    (.configureBlocking channel false)
    (.connect channel peer-addr)

    ;; Start IO Loop
    (let [t (Thread.
              (fn []
                (try
                  (run-loop channel selector engine peer-addr connection)
                  (catch Exception e
                    (println "Connection Loop Error:" e)))))]
      (.start t))

    ;; Send SCTP INIT
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

(defn listen [port]
  (let [cert-data (dtls/generate-cert)
        ctx (dtls/create-ssl-context (:cert cert-data) (:key cert-data))
        engine (dtls/create-engine ctx false) ;; Server mode
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
                    :on-open (atom nil)}]
    (.configureBlocking channel false)
    (.bind channel (InetSocketAddress. port))

    (let [t (Thread.
              (fn []
                (try
                  (let [temp-buf (make-buffer)
                        peer-addr (do
                                    (.configureBlocking channel true)
                                    (let [addr (.receive channel temp-buf)]
                                      (.configureBlocking channel false)
                                      (.flip temp-buf)
                                      addr))]
                    (println "Accepted connection from" peer-addr)
                    #_(println "Received UDP packet from" peer-addr "bytes:" (.limit temp-buf))

                    (let [app-in (make-buffer)
                          res (.unwrap engine temp-buf app-in)]
                       (when (= (.getStatus res) SSLEngineResult$Status/OK)
                          (.flip app-in)
                          (when (.hasRemaining app-in)
                             (println "Got data on first packet?"))))

                    (run-loop channel selector engine peer-addr connection temp-buf))
                  (catch Exception e
                    (println "Server Loop Error:" e)))))]
      (.start t))

    connection))

(defn send-msg [connection msg]
  (let [state (:state connection)
        ver-tag (:remote-ver-tag @state)
        tsn (:next-tsn @state)
        ssn (:ssn @state)
        _ (swap! state #(-> %
                            (update :next-tsn inc)
                            (update :ssn inc)))
        packet {:src-port 5000
                :dst-port 5000
                :verification-tag ver-tag
                :chunks [{:type :data
                          :tsn tsn
                          :stream-id 0
                          :seq-num ssn
                          :protocol :webrtc/string
                          :payload (.getBytes msg "UTF-8")}]}]
     (.offer (:sctp-out connection) packet)))
