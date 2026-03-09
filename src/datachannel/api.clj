(ns datachannel.api
  (:require [datachannel.core :as dc]
            [datachannel.nio :as nio]
            [datachannel.sdp :as sdp]
            [datachannel.dtls :as dtls])
  (:import [java.nio ByteBuffer]
           [java.nio.channels DatagramChannel Selector SelectionKey]
           [java.net InetSocketAddress]))

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
     :remote-addr (atom nil)}))

(defn- apply-action!
  "Applies a pure action function to the state atom sequentially using a lock, extracts effects, and executes them."
  [node action-fn callbacks]
  (let [effects (locking (:state-atom node)
                  (let [st @(:state-atom node)
                        res (action-fn st)
                        res-ser (dc/serialize-network-out res)
                        new-st (:new-state res-ser)
                        was-established? (:established-notified? new-st)
                        is-established? (= (:state new-st) :established)
                        should-notify? (and is-established? (not was-established?))
                        final-st (if should-notify? (assoc new-st :established-notified? true) new-st)]
                    (reset! (:state-atom node) final-st)
                    (assoc res-ser :should-notify-open? should-notify?)))]
    (let [{:keys [network-out-bytes app-events should-notify-open?]} effects
          channel @(:channel node)
          remote-addr @(:remote-addr node)]
      (when (and channel remote-addr)
        (doseq [^ByteBuffer buf network-out-bytes]
          (.send channel buf remote-addr)))
      (doseq [evt app-events]
        (case (:type evt)
          :on-message (when-let [cb (:on-message callbacks)]
                        (cb evt))
          :on-error   (when-let [cb (:on-error callbacks)]
                        (cb evt))
          nil))
      (when should-notify-open?
        (when-let [cb (:on-open callbacks)]
          (cb))))))

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
                             :ice-pwd (:pwd (:ice-creds node))})
                     client-mode?)

        ;; Set up networking
        ^DatagramChannel channel (nio/create-non-blocking-channel local-port)
        ^Selector selector (nio/create-selector)
        remote-ip (:ip remote-sdp-params)
        remote-port (:port remote-sdp-params)
        remote-addr (InetSocketAddress. remote-ip remote-port)]

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

(defn send!
  "Sends a message (string or byte array) to the connected peer."
  [node message]
  (let [payload (if (string? message)
                  (.getBytes ^String message "UTF-8")
                  message)
        protocol (if (string? message) :webrtc/string :webrtc/binary)]
    (apply-action! node
                   (fn [st]
                     (dc/send-data st payload 0 protocol (System/currentTimeMillis)))
                   {})))

(defn close!
  "Gracefully shuts down the background loop, the socket, and the selector."
  [node]
  (when @(:running node)
    (reset! (:running node) false)
    (apply-action! node
                   (fn [st]
                     (dc/handle-event st {:type :shutdown} (System/currentTimeMillis)))
                   {})
    (when-let [fut @(:loop-future node)]
      (future-cancel fut))
    (when-let [^DatagramChannel channel @(:channel node)]
      (when (.isOpen channel)
        (.close channel)))
    (when-let [^Selector selector @(:selector node)]
      (when (.isOpen selector)
        (.close selector)))
    (reset! (:channel node) nil)
    (reset! (:selector node) nil)
    (reset! (:loop-future node) nil)))
