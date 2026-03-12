(ns datachannel.api
  (:require [datachannel.core :as dc]
            [datachannel.nio :as nio]
            [datachannel.sdp :as sdp]
            [datachannel.dtls :as dtls]
            [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.nio.channels DatagramChannel Selector SelectionKey]
           [java.net InetSocketAddress]))

(defn set-callbacks!
  "Sets the callbacks for a given node dynamically."
  [node callbacks]
  (reset! (:callbacks node) callbacks))

(defn create-node
  "Prepares a node without starting the network loop.
  `opts` should contain `:port` and optionally `:setup` (e.g. \"active\" or \"passive\")."
  [opts]
  (let [ice-creds (sdp/generate-ice-credentials)
        cert-data (dtls/generate-cert)
        port (get opts :port 5000)
        setup (get opts :setup "passive")
        local-sdp-params {:port port
                          :ice-ufrag (:ufrag ice-creds)
                          :ice-pwd (:pwd ice-creds)
                          :fingerprint (:fingerprint cert-data)
                          :setup setup}]
    {:opts opts
     :cert-data cert-data
     :ice-creds ice-creds
     :local-sdp-params local-sdp-params
     :running (atom false)
     :state-atom (atom nil)
     :channel (atom nil)
     :selector (atom nil)
     :loop-future (atom nil)
     :remote-addr (atom nil)
     :shared-channel? false
     :callbacks (atom nil)}))

(defn- apply-action!
  "Applies a pure action function to the state atom sequentially using a lock, extracts effects, and executes them."
  [node action-fn default-callbacks]
  (let [callbacks (or @(:callbacks node) default-callbacks)
        state-atom (:state-atom node)
        effects (locking state-atom
                  (let [st @state-atom
                        res (action-fn st)
                        res-ser (dc/serialize-network-out res)
                        new-st (:new-state res-ser)
                        was-established? (:established-notified? new-st)
                        is-established? (= (:state new-st) :established)
                        should-notify? (and is-established? (not was-established?))
                        final-st (if should-notify? (assoc new-st :established-notified? true) new-st)]
                    (reset! state-atom final-st)
                    (assoc res-ser :should-notify-open? should-notify?)))
        {:keys [network-out-bytes app-events should-notify-open? new-state]} effects
        channel @(:channel node)]
    (let [pure-remote-addr (:remote-addr new-state)]
      (when (and pure-remote-addr (not= pure-remote-addr @(:remote-addr node)))
        (reset! (:remote-addr node) pure-remote-addr)))
    (when channel
      (doseq [buf network-out-bytes]
        (if (and (map? buf) (:packet buf))
          (let [{:keys [packet target]} buf
                dest-addr (if (string? target)
                            (let [[ip port] (str/split target #":")]
                              (InetSocketAddress. ^String ip (Integer/parseInt port)))
                            (if (and (map? target) (:ip target) (:port target))
                              (InetSocketAddress. ^String (:ip target) (Integer/parseInt (str (:port target))))
                              target))
                wrapped-packet (if (instance? ByteBuffer packet) packet (ByteBuffer/wrap packet))]
            (.send channel ^ByteBuffer wrapped-packet dest-addr))
          (let [dest-addr @(:remote-addr node)]
            (when dest-addr
              (.send channel ^ByteBuffer buf dest-addr))))))
    (doseq [evt app-events]
      (case (:type evt)
        :on-message (when-let [cb (:on-message callbacks)]
                      (cb (assoc evt :is-string? (= (:protocol evt) :webrtc/string))))
        :on-error   (when-let [cb (:on-error callbacks)]
                      (cb evt))
        :on-closing (when-let [cb (:on-closing callbacks)]
                      (cb evt))
        :on-close   (when-let [cb (:on-close callbacks)]
                      (cb evt))
        :on-data-channel (when-let [cb (:on-data-channel callbacks)]
                           (cb evt))
        :on-open    (when-let [cb (:on-open callbacks)]
                      (when (:channel-id evt)
                        (cb evt)))
        :on-buffered-amount-low (when-let [cb (:on-buffered-amount-low callbacks)]
                                  (cb evt))
        :on-buffered-amount-high (when-let [cb (:on-buffered-amount-high callbacks)]
                                   (cb evt))
        :on-total-buffered-amount-low (when-let [cb (:on-total-buffered-amount-low callbacks)]
                                        (cb evt))
        :on-ice-candidate (when-let [cb (:on-ice-candidate callbacks)]
                            (cb evt))
        :on-ice-connection-state-change (when-let [cb (:on-ice-connection-state-change callbacks)]
                                          (cb evt))
        :on-ice-gathering-state-change (when-let [cb (:on-ice-gathering-state-change callbacks)]
                                         (cb evt))
        nil))
    (when should-notify-open?
      (when-let [cb (:on-open callbacks)]
        (cb {})))))

(defn start!
  "Ignites the node. Connects via NIO, starts the loop, and triggers callbacks.
  `remote-sdp-params` must contain `:ip`, `:port`."
  [node remote-sdp-params callbacks]
  (let [local-port (get-in node [:local-sdp-params :port])
        local-setup (get-in node [:local-sdp-params :setup])
        client-mode? (= local-setup "active")

        ;; Initialize pure connection state
        conn-state (dc/create-connection
                    (merge (:opts node)
                           {:cert-data (:cert-data node)
                            :ice-ufrag (:ufrag (:ice-creds node))
                            :ice-pwd (:pwd (:ice-creds node))
                            :remote-ice-ufrag (:remote-ice-ufrag remote-sdp-params)
                            :remote-ice-pwd (:remote-ice-pwd remote-sdp-params)
                            :remote-candidates (if (and (:ip remote-sdp-params) (:port remote-sdp-params))
                                                 [{:ip (:ip remote-sdp-params) :port (:port remote-sdp-params)}]
                                                 [])})
                    client-mode?)

        ;; Set up networking
        ^DatagramChannel channel (nio/create-non-blocking-channel local-port)
        ^Selector selector (nio/create-selector)
        remote-ip (:ip remote-sdp-params)
        remote-port (:port remote-sdp-params)
        remote-addr (if (and remote-ip remote-port)
                      (InetSocketAddress. ^String remote-ip (int remote-port))
                      nil)]

    (nio/register-for-read channel selector)

    (reset! (:channel node) channel)
    (reset! (:selector node) selector)
    (reset! (:state-atom node) conn-state)
    (reset! (:running node) true)
    (reset! (:remote-addr node) remote-addr)

    ;; Start the loop
    (let [fut
          (future
            (try
              (let [recv-buf (ByteBuffer/allocateDirect 65536)]
                (while @(:running node)
                  (let [ready-channels (.select selector 10)
                        now-ms (System/currentTimeMillis)]

                    ;; Process I/O
                    (when (> ready-channels 0)
                      (let [selected-keys (.selectedKeys selector)
                            iter (.iterator selected-keys)]
                        (while (.hasNext iter)
                          (let [^SelectionKey key (.next iter)]
                            (.remove iter)
                            (when (.isReadable key)
                              (.clear recv-buf)
                              (let [sender-addr (.receive channel recv-buf)]
                                (when sender-addr
                                  (.flip recv-buf)
                                  (let [len (.remaining recv-buf)
                                        bytes-arr (byte-array len)]
                                    (.get recv-buf bytes-arr)
                                    (apply-action! node
                                                   (fn [st]
                                                     (dc/handle-receive st bytes-arr now-ms sender-addr))
                                                   callbacks)))))))))

                    ;; Process timeouts
                    (let [current-state @(:state-atom node)
                          timers (:timers current-state)]
                      (doseq [[timer-id timer] timers]
                        (when (>= now-ms (:expires-at timer))
                          (apply-action! node
                                         (fn [st]
                                           (dc/handle-timeout st timer-id now-ms (:dtls/engine st)))
                                         callbacks)))))))
              (catch Exception e
                (println "Error in api loop:" (.getMessage e))
                (when-let [cb (:on-error callbacks)]
                  (cb {:type :on-error :cause e})))))]
      (reset! (:loop-future node) fut)

      ;; If we are the active side, we should initiate connection
      (when client-mode?
        (future
          (Thread/sleep 100)
          (apply-action! node
                         (fn [st]
                           (dc/handle-timeout st :stun/keepalive (System/currentTimeMillis) nil))
                         callbacks)
          (apply-action! node
                         (fn [st]
                           (dc/handle-timeout st :dtls/flight-timeout (System/currentTimeMillis) (:dtls/engine st)))
                         callbacks)
          (apply-action! node
                         (fn [st]
                           (dc/handle-event st {:type :connect} (System/currentTimeMillis)))
                         callbacks)))
      node)))

(defn get-buffered-amount
  "Returns the current size (in bytes) of the send queue for a given stream."
  [node stream-id]
  (let [state @(:state-atom node)]
    (dc/get-buffered-amount state stream-id)))

(defn get-state
  "Returns the current state of the connection (e.g., :established, :closed)."
  [node]
  (:state @(:state-atom node)))

(defn get-stats
  "Returns the :metrics map from the state atom."
  [node]
  (:metrics @(:state-atom node)))

(defn add-remote-candidate!
  "Adds a remote ICE candidate to the pure connection state for active probing."
  [node ip port]
  (apply-action! node
                 (fn [st]
                   (datachannel.core/handle-event st {:type :add-remote-candidate :ip ip :port port} (System/currentTimeMillis)))
                 {}))

(defn create-data-channel!
  "Creates a new data channel matching the W3C RTCDataChannel specification.
  Returns the assigned channel id."
  [node label options]
  (let [id-box (atom nil)]
    (apply-action! node
                   (fn [st]
                     (let [res (dc/create-data-channel st label options)]
                       (reset! id-box (:channel-id res))
                       res))
                   {})
    @id-box))

(defn set-max-message-size!
  "Sets the maximum message size that can be sent over the connection."
  [node max-size]
  (apply-action! node
                 (fn [st]
                   (dc/set-max-message-size st max-size))
                 {}))

(defn send!
  "Sends a message (string or byte array) to the connected peer over the given channel-id."
  [node message channel-id]
  (let [payload (if (string? message)
                  (.getBytes ^String message "UTF-8")
                  message)
        protocol (if (string? message) :webrtc/string :webrtc/binary)]
    (apply-action! node
                   (fn [st]
                     (dc/send-data st payload channel-id protocol (System/currentTimeMillis)))
                   {})))

(defn close!
  "Gracefully shuts down the background loop, the socket, and the selector."
  [node]
  (when @(:running node)
    (reset! (:running node) false)
    (when (:state-atom node)
      (apply-action! node
                     (fn [st]
                       (dc/handle-event st {:type :shutdown} (System/currentTimeMillis)))
                     {}))
    (when-let [fut @(:loop-future node)]
      (future-cancel fut))
    (when-not (:shared-channel? node)
      (when-let [^DatagramChannel channel @(:channel node)]
        (when (.isOpen channel)
          (.close channel)))
      (when-let [^Selector selector @(:selector node)]
        (when (.isOpen selector)
          (.close selector))))
    (reset! (:channel node) nil)
    (reset! (:selector node) nil)
    (reset! (:loop-future node) nil)))

(defn listen!
  "Starts a UDP listener on the given port that can multiplex multiple incoming connections.
  `opts` should contain `:port`.
  `listener-callbacks` should contain `:on-connection` which receives a new child node."
  [opts listener-callbacks]
  (let [port (get opts :port 5000)
        ice-creds (or (:ice-creds opts) (sdp/generate-ice-credentials))
        cert-data (or (:cert-data opts) (dtls/generate-cert))
        ^DatagramChannel channel (nio/create-non-blocking-channel port)
        ^Selector selector (nio/create-selector)
        routing-table (atom {})
        running (atom true)
        listener-node {:opts opts
                       :running running
                       :channel (atom channel)
                       :selector (atom selector)
                       :routing-table routing-table
                       :loop-future (atom nil)
                       :shared-channel? false
                       :ice-creds ice-creds
                       :cert-data cert-data}]
    (nio/register-for-read channel selector)
    (let [fut
          (future
            (try
              (let [recv-buf (ByteBuffer/allocateDirect 65536)]
                (while @running
                  (let [ready-channels (.select selector 10)
                        now-ms (System/currentTimeMillis)]
                    (when (> ready-channels 0)
                      (let [selected-keys (.selectedKeys selector)
                            iter (.iterator selected-keys)]
                        (while (.hasNext iter)
                          (let [^SelectionKey key (.next iter)]
                            (.remove iter)
                            (when (.isReadable key)
                              (.clear recv-buf)
                              (let [sender-addr (.receive channel recv-buf)]
                                (when sender-addr
                                  (.flip recv-buf)
                                  (let [len (.remaining recv-buf)
                                        bytes-arr (byte-array len)]
                                    (.get recv-buf bytes-arr)
                                    (let [child-node (or (get @routing-table sender-addr)
                                                         (let [new-node {:opts opts
                                                                         :cert-data cert-data
                                                                         :ice-creds ice-creds
                                                                         :local-sdp-params {:port port
                                                                                            :ice-ufrag (:ufrag ice-creds)
                                                                                            :ice-pwd (:pwd ice-creds)
                                                                                            :fingerprint (:fingerprint cert-data)
                                                                                            :setup "passive"}
                                                                         :running (atom true)
                                                                         :state-atom (atom (dc/create-connection
                                                                                            (merge opts
                                                                                                   {:cert-data cert-data
                                                                                                    :ice-ufrag (:ufrag ice-creds)
                                                                                                    :ice-pwd (:pwd ice-creds)})
                                                                                            false))
                                                                         :channel (atom channel)
                                                                         :selector (atom selector)
                                                                         :loop-future (atom nil)
                                                                         :remote-addr (atom sender-addr)
                                                                         :shared-channel? true
                                                                         :callbacks (atom {})}]
                                                           (swap! routing-table assoc sender-addr new-node)
                                                           (when-let [cb (:on-connection listener-callbacks)]
                                                             (cb new-node))
                                                           new-node))]
                                      (apply-action! child-node
                                                     (fn [st]
                                                       (dc/handle-receive st bytes-arr now-ms sender-addr))
                                                     @(:callbacks child-node)))))))))))
                    (doseq [[_ child-node] @routing-table]
                      (let [current-state @(:state-atom child-node)
                            timers (:timers current-state)]
                        (doseq [[timer-id timer] timers]
                          (when (>= now-ms (:expires-at timer))
                            (apply-action! child-node
                                           (fn [st]
                                             (dc/handle-timeout st timer-id now-ms (:dtls/engine st)))
                                           @(:callbacks child-node)))))))))
              (catch Exception e
                (println "Error in api listen loop:" (.getMessage e))
                (when-let [cb (:on-error listener-callbacks)]
                  (cb {:type :on-error :cause e})))))]
      (reset! (:loop-future listener-node) fut)
      listener-node)))
