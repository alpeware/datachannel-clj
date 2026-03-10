(ns datachannel.handlers
  (:require [datachannel.packetize :as packetize]
            [datachannel.stun :as stun]
            [datachannel.dtls :as dtls]))

(defmulti handle-timeout-timer (fn [_state timer-id _now-ms] timer-id))

(defmethod handle-timeout-timer :sctp/t2-shutdown [state _timer-id now-ms]
  (let [timer (get-in state [:timers :sctp/t2-shutdown])]
    (if-not timer
      {:new-state state :app-events []}
      (let [retries (:retries timer)]
        (if (>= retries 8)
          (let [abort-chunk {:type :abort}]
            {:new-state (-> state
                            (assoc :state :closed)
                            (update :timers dissoc :sctp/t2-shutdown)
                            (update :pending-control-chunks conj abort-chunk))
             :app-events [{:type :on-error :cause :max-retransmissions} {:type :on-close}]})
          (let [new-delay (* (:delay timer) 2)
                new-delay (min new-delay 60000)
                packet (:packet timer)]
            {:new-state (-> state
                            (assoc-in [:timers :sctp/t2-shutdown]
                                      {:expires-at (+ now-ms new-delay)
                                       :delay new-delay
                                       :retries (inc retries)
                                       :packet packet})
                            (update :pending-control-chunks into (:chunks packet)))
             :app-events []}))))))

(defmethod handle-timeout-timer :sctp/t1-init [state _timer-id now-ms]
  (let [timer (get-in state [:timers :sctp/t1-init])
        retries (:retries timer)]
    (if (>= retries 8)
      {:new-state (-> state
                      (assoc :state :closed)
                      (update :timers dissoc :sctp/t1-init))
       :app-events [{:type :on-error :cause :max-retransmissions} {:type :on-close}]}
      (let [new-delay (* (:delay timer) 2)
            new-delay (min new-delay 60000) ;; Cap delay
            packet (:packet timer)]
        {:new-state (-> state
                        (assoc-in [:timers :sctp/t1-init]
                                  {:expires-at (+ now-ms new-delay)
                                   :delay new-delay
                                   :retries (inc retries)
                                   :packet packet})
                        (update :pending-control-chunks into (:chunks packet)))
         :app-events []}))))

(defmethod handle-timeout-timer :sctp/t1-cookie [state _timer-id now-ms]
  (let [timer (get-in state [:timers :sctp/t1-cookie])
        retries (:retries timer)]
    (if (>= retries 8)
      {:new-state (-> state
                      (assoc :state :closed)
                      (update :timers dissoc :sctp/t1-cookie))
       :app-events [{:type :on-error :cause :max-retransmissions} {:type :on-close}]}
      (let [new-delay (* (:delay timer) 2)
            new-delay (min new-delay 60000) ;; Cap delay
            packet (:packet timer)]
        {:new-state (-> state
                        (assoc-in [:timers :sctp/t1-cookie]
                                  {:expires-at (+ now-ms new-delay)
                                   :delay new-delay
                                   :retries (inc retries)
                                   :packet packet})
                        (update :pending-control-chunks into (:chunks packet)))
         :app-events []}))))

(defmethod handle-timeout-timer :sctp/t3-rtx [state _timer-id now-ms]
  (let [timer (get-in state [:timers :sctp/t3-rtx])
        active-streams (filter #(seq (:send-queue (val %))) (:streams state))]
    (if (empty? active-streams)
      ;; If queue is empty, stop the timer
      {:new-state (update state :timers dissoc :sctp/t3-rtx) :app-events []}
      (let [[stream-id stream-data] (first active-streams)
            q (:send-queue stream-data)
            first-item (first q)
            retries (:retries first-item)
            global-max-retries (get state :max-retransmissions 10)
            item-max-retries (:max-retransmits first-item)
            max-retries (if item-max-retries item-max-retries global-max-retries)]
        (if (>= retries max-retries)
          (if item-max-retries
            ;; Partial reliability: abandon message and send FORWARD-TSN
            (let [;; Find all chunks belonging to the same user message (until we see ending flag)
                  ;; SCTP flags: B=2, E=1. If B is not set, we might be in the middle of a message.
                  ;; For simplicity, we drop from the front until we hit an item where (bit-and flags 1) is non-zero (ending=true)
                  dropped-items (loop [items []
                                       rem-q q]
                                  (if (empty? rem-q)
                                    items
                                    (let [it (first rem-q)
                                          flags (get-in it [:chunk :flags] 3)]
                                      (if (pos? (bit-and flags 1)) ; ending flag set
                                        (conj items it)
                                        (recur (conj items it) (rest rem-q))))))
                  new-q (vec (drop (count dropped-items) q))
                  last-dropped-tsn (:tsn (last dropped-items))
                  ;; Only subtract bytes for chunks that were actually sent (in flight)
                  abandoned-bytes (reduce + (map #(if (:sent? %) (+ 16 (if (:payload (:chunk %)) (alength ^bytes (:payload (:chunk %))) 0)) 0) dropped-items))
                  new-flight-size (max 0 (- (get state :flight-size 0) abandoned-bytes))
                  ;; Extract stream-id and seq-num from the first dropped item for the FORWARD-TSN payload
                  stream-id-to-fwd (get-in (first dropped-items) [:chunk :stream-id] stream-id)
                  seq-num-to-fwd (get-in (first dropped-items) [:chunk :seq-num] 0)
                  forward-tsn-chunk {:type :forward-tsn :new-cumulative-tsn last-dropped-tsn :streams [{:stream-id stream-id-to-fwd :stream-sequence seq-num-to-fwd}]}
                  s1 (-> state
                         (assoc-in [:streams stream-id :send-queue] new-q)
                         (assoc :flight-size new-flight-size)
                         (update :pending-control-chunks conj forward-tsn-chunk))
                  s2 (if (empty? new-q)
                       (update s1 :timers dissoc :sctp/t3-rtx)
                       s1)

                  old-buffered (reduce + (map #(if-let [p (:payload (:chunk %))] (alength ^bytes p) 0) q))
                  new-buffered (reduce + (map #(if-let [p (:payload (:chunk %))] (alength ^bytes p) 0) new-q))
                  low-threshold (get state :buffered-amount-low-threshold 0)
                  app-events (if (and (> old-buffered low-threshold) (<= new-buffered low-threshold))
                               [{:type :on-buffered-amount-low :stream-id stream-id}]
                               [])]
              {:new-state s2 :app-events app-events})
            ;; Standard SCTP: abort connection
            (let [abort-chunk {:type :abort}]
              {:new-state (-> state
                              (assoc :state :closed)
                              (update :timers dissoc :sctp/t3-rtx)
                              (update :pending-control-chunks conj abort-chunk))
               :app-events [{:type :on-error :cause :max-retransmissions} {:type :on-close}]}))
          (let [new-delay (* (:delay timer) 2)
                new-delay (min new-delay 60000)
                ;; Mark sent? false so packetize will re-transmit it
                updated-item (assoc first-item :retries (inc retries) :sent? false)
                new-q (assoc q 0 updated-item)

                ;; CC backoff
                cwnd (get state :cwnd 1000000)
                mtu (get state :mtu 1200)
                new-ssthresh (max (quot cwnd 2) (* 4 mtu))
                new-cwnd (* 1 mtu)

                ;; flight-size logic: decrease flight-size because we mark it sent?=false
                chunk-size (+ 16 (if (:payload (:chunk first-item)) (alength ^bytes (:payload (:chunk first-item))) 0))
                flight-size (max 0 (- (get state :flight-size 0) chunk-size))]
            {:new-state (-> state
                            (assoc-in [:timers :sctp/t3-rtx] {:expires-at (+ now-ms new-delay) :delay new-delay})
                            (update-in [:metrics :retransmissions] (fnil inc 0))
                            (assoc-in [:streams stream-id :send-queue] new-q)
                            (assoc :ssthresh new-ssthresh)
                            ;; Set cwnd to accommodate at least the MTU, fast retransmit logic requires flight-size to drop.
                            ;; If flight-size + chunk-size > cwnd, packetize won't pull.
                            ;; Here we set cwnd to something large enough, or just MTU but ensure flight-size is updated properly.
                            (assoc :cwnd (max new-cwnd (+ flight-size chunk-size)))
                            (assoc :flight-size flight-size))
             :app-events []}))))))

(defmethod handle-timeout-timer :sctp/t-heartbeat [state _timer-id now-ms]
  (let [interval (get state :heartbeat-interval 30000)
        rto (get state :rto-initial 1000)
        hb-chunk {:type :heartbeat
                  :params [{:type :heartbeat-info :info (byte-array 8)}]}]
    {:new-state (-> state
                    (assoc-in [:timers :sctp/t-heartbeat] {:expires-at (+ now-ms interval)})
                    (assoc-in [:timers :sctp/t-heartbeat-rtx] {:expires-at (+ now-ms rto)})
                    (update :pending-control-chunks conj hb-chunk))
     :app-events []}))

(defmethod handle-timeout-timer :sctp/t-heartbeat-rtx [state _timer-id _now-ms]
  (let [errors (get state :heartbeat-error-count 0)
        max-retries (get state :max-retransmissions 10)
        new-errors (inc errors)]
    (if (> new-errors max-retries)
      (let [abort-chunk {:type :abort}]
        {:new-state (-> state
                        (assoc :state :closed)
                        (update :timers dissoc :sctp/t-heartbeat :sctp/t-heartbeat-rtx)
                        (update :pending-control-chunks conj abort-chunk))
         :app-events [{:type :on-error :cause :max-retransmissions} {:type :on-close}]})
      {:new-state (-> state
                      (assoc :heartbeat-error-count new-errors)
                      (update :timers dissoc :sctp/t-heartbeat-rtx))
       :app-events []})))

(defmethod handle-timeout-timer :stun/keepalive [state _timer-id now-ms]
  (let [req (stun/make-simple-binding-request)]
    {:new-state (assoc-in state [:timers :stun/keepalive] {:expires-at (+ now-ms 15000)})
     :network-out [req]
     :app-events []}))

(defmethod handle-timeout-timer :dtls/flight-timeout [state _timer-id now-ms]
  (if-let [^javax.net.ssl.SSLEngine engine (:dtls/engine state)]
    (let [hs-status (.getHandshakeStatus engine)]
      (if (or (= hs-status javax.net.ssl.SSLEngineResult$HandshakeStatus/FINISHED)
              (= hs-status javax.net.ssl.SSLEngineResult$HandshakeStatus/NOT_HANDSHAKING))
        {:new-state (update state :timers dissoc :dtls/flight-timeout)
         :network-out []
         :app-events []}
        (let [out-buf (java.nio.ByteBuffer/allocateDirect 65536)
              empty-in (java.nio.ByteBuffer/allocateDirect 0)
              {:keys [packets]} (dtls/handshake engine empty-in out-buf)]
          {:new-state (assoc-in state [:timers :dtls/flight-timeout] {:expires-at (+ now-ms 1000)})
           :network-out (vec packets)
           :app-events []})))
    {:new-state (update state :timers dissoc :dtls/flight-timeout)
     :network-out []
     :app-events []}))

(defmethod handle-timeout-timer :default [state _timer-id _now-ms]
  {:new-state state :app-events []})

(defn handle-timeout [state timer-id now-ms & [^javax.net.ssl.SSLEngine _engine]]
  (let [res (handle-timeout-timer state timer-id now-ms)]
    (if (or (seq (:pending-control-chunks (:new-state res)))
            (= "sctp" (namespace timer-id)))
      (packetize/packetize (:new-state res) (:app-events res))
      res)))

(defmulti handle-event-type (fn [_state event _now-ms] (:type event)))

(defmethod handle-event-type :connect [state _event now-ms]
  (let [{:keys [local-ver-tag initial-tsn next-tsn]} state
        init-tsn (or initial-tsn next-tsn 0)
        init-chunk {:type :init
                    :init-tag local-ver-tag
                    :a-rwnd 100000
                    :outbound-streams 10
                    :inbound-streams 10
                    :initial-tsn init-tsn
                    :params {}}
        init-packet {:src-port 5000
                     :dst-port 5000
                     :verification-tag 0
                     :chunks [init-chunk]}]
    {:new-state (-> state
                    (assoc :state :cookie-wait)
                    (assoc :next-tsn init-tsn)
                    (assoc-in [:timers :sctp/t1-init] {:expires-at (+ now-ms 3000) :delay 3000 :retries 0 :packet init-packet})
                    (update :pending-control-chunks conj init-chunk)
                    (update-in [:metrics :tx-packets] (fnil inc 0)))
     :app-events []}))

(defmethod handle-event-type :shutdown [state _event now-ms]
  (if (= (:state state) :established)
    (if (every? empty? (map :send-queue (vals (:streams state))))
      (let [shutdown-chunk {:type :shutdown}
            shutdown-packet {:src-port (get state :local-port 5000)
                             :dst-port (get state :remote-port 5000)
                             :verification-tag (:remote-ver-tag state)
                             :chunks [shutdown-chunk]}
            new-state (-> state
                          (assoc :state :shutdown-sent)
                          (assoc-in [:timers :sctp/t2-shutdown] {:expires-at (+ now-ms 3000) :delay 3000 :retries 0 :packet shutdown-packet})
                          (update :pending-control-chunks conj shutdown-chunk))]
        {:new-state new-state :app-events [{:type :on-closing}]})
      {:new-state (assoc state :state :shutdown-pending) :app-events [{:type :on-closing}]})
    {:new-state state :app-events []}))

(defmethod handle-event-type :default [state _event _now-ms]
  {:new-state state :app-events []})

(defn handle-event [state event now-ms]
  (let [res (handle-event-type state event now-ms)]
    (packetize/packetize (:new-state res) (:app-events res))))
