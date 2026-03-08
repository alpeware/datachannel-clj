(ns datachannel.core
  (:require [datachannel.sctp :as sctp]
            [datachannel.dtls :as dtls]
            [datachannel.stun :as stun])
  (:import [java.nio ByteBuffer]
           [java.net InetSocketAddress StandardSocketOptions]
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

(defn handle-event [state event now-ms]
  (case (:type event)
    :connect
    (let [{:keys [local-ver-tag initial-tsn]} state
          init-packet {:src-port 5000
                       :dst-port 5000
                       :verification-tag 0
                       :chunks [{:type :init
                                 :init-tag local-ver-tag
                                 :a-rwnd 100000
                                 :outbound-streams 10
                                 :inbound-streams 10
                                 :initial-tsn initial-tsn
                                 :params {}}]}]
      {:new-state (-> state
                      (assoc :state :cookie-wait)
                      (assoc-in [:timers :t1-init] {:expires-at (+ now-ms 3000) :delay 3000 :retries 0 :packet init-packet})
                      (update-in [:metrics :tx-packets] (fnil inc 0)))
       :network-out [init-packet] :app-events []})

    {:new-state state :network-out [] :app-events []}))

(defn handle-timeout [state timer-id now-ms]
  (case timer-id
    :t2-shutdown
    (let [timer (get-in state [:timers :t2-shutdown])]
      (if-not timer
        {:new-state state :network-out [] :app-events []}
        (let [retries (:retries timer)]
          (if (>= retries 8)
            {:new-state (-> state
                            (assoc :state :closed)
                            (update :timers dissoc :t2-shutdown))
             :network-out [{:src-port (:src-port (:packet timer))
                            :dst-port (:dst-port (:packet timer))
                            :verification-tag (:remote-ver-tag state)
                            :chunks [{:type :abort}]}]
             :app-events [{:type :on-error :cause :max-retransmissions}]}
            (let [new-delay (* (:delay timer) 2)
                  new-delay (min new-delay 60000)
                  packet (:packet timer)]
              {:new-state (assoc-in state [:timers :t2-shutdown]
                                    {:expires-at (+ now-ms new-delay)
                                     :delay new-delay
                                     :retries (inc retries)
                                     :packet packet})
               :network-out [packet] :app-events []})))))

    :t1-init
    (let [timer (get-in state [:timers :t1-init])
          retries (:retries timer)]
      (if (>= retries 8)
        {:new-state (-> state
                        (assoc :state :closed)
                        (update :timers dissoc :t1-init))
         :network-out [] :app-events [{:type :on-error :cause :max-retransmissions}]}
        (let [new-delay (* (:delay timer) 2)
              new-delay (min new-delay 60000) ;; Cap delay
              packet (:packet timer)]
          {:new-state (assoc-in state [:timers :t1-init]
                                {:expires-at (+ now-ms new-delay)
                                 :delay new-delay
                                 :retries (inc retries)
                                 :packet packet})
           :network-out [packet] :app-events []})))

    :t3-rtx
    (let [timer (get-in state [:timers :t3-rtx])
          q (:tx-queue state)]
      (if (empty? q)
        ;; If queue is empty, stop the timer
        {:new-state (update state :timers dissoc :t3-rtx) :network-out [] :app-events []}
        (let [first-item (first q)
              retries (:retries first-item)
              max-retries (get state :max-retransmissions 10)]
          (if (>= retries max-retries)
            {:new-state (-> state
                            (assoc :state :closed)
                            (update :timers dissoc :t3-rtx))
             :network-out [{:src-port (:src-port (:packet first-item))
                            :dst-port (:dst-port (:packet first-item))
                            :verification-tag (:remote-ver-tag state)
                            :chunks [{:type :abort}]}]
             :app-events [{:type :on-error :cause :max-retransmissions}]}
            (let [packet (assoc (:packet first-item) :verification-tag (:remote-ver-tag state))
                  new-delay (* (:delay timer) 2)
                  new-delay (min new-delay 60000)
                  updated-item (assoc first-item :retries (inc retries) :packet packet)
                  new-q (assoc q 0 updated-item)]
              {:new-state (-> state
                              (assoc :tx-queue new-q)
                              (assoc-in [:timers :t3-rtx] {:expires-at (+ now-ms new-delay) :delay new-delay})
                              (update-in [:metrics :retransmissions] (fnil inc 0)))
               :network-out [packet] :app-events []})))))

    :t-heartbeat
    (let [interval (get state :heartbeat-interval 30000)
          rto (get state :rto-initial 1000)
          packet {:src-port 5000
                  :dst-port 5000
                  :verification-tag (:remote-ver-tag state)
                  :chunks [{:type :heartbeat
                            :params [{:type :heartbeat-info :info (byte-array 8)}]}]}]
      {:new-state (-> state
                      (assoc-in [:timers :t-heartbeat] {:expires-at (+ now-ms interval)})
                      (assoc-in [:timers :t-heartbeat-rtx] {:expires-at (+ now-ms rto)}))
       :network-out [packet] :app-events []})

    :t-heartbeat-rtx
    (let [errors (get state :heartbeat-error-count 0)
          max-retries (get state :max-retransmissions 10)
          new-errors (inc errors)]
      (if (> new-errors max-retries)
        {:new-state (-> state
                        (assoc :state :closed)
                        (update :timers dissoc :t-heartbeat :t-heartbeat-rtx))
         :network-out [{:src-port 5000
                        :dst-port 5000
                        :verification-tag (:remote-ver-tag state)
                        :chunks [{:type :abort}]}]
         :app-events [{:type :on-error :cause :max-retransmissions}]}
        {:new-state (-> state
                        (assoc :heartbeat-error-count new-errors)
                        (update :timers dissoc :t-heartbeat-rtx))
         :network-out [] :app-events []}))

    {:new-state state :network-out [] :app-events []}))


(defn handle-receive [state network-bytes now-ms]
  {:new-state state :network-out [] :app-events []})

(defmulti process-chunk (fn [state chunk packet now-ms] (:type chunk)))

(defmethod process-chunk :data [state chunk packet now-ms]
  (let [proto (:protocol chunk)
        tsn (:tsn chunk)
        s1 (if (pos? (unchecked-int (unchecked-subtract (unchecked-int tsn) (unchecked-int (get state :remote-tsn -1)))))
             (assoc state :remote-tsn tsn)
             state)
        sack-packet {:src-port (:dst-port packet)
                     :dst-port (:src-port packet)
                     :verification-tag (:remote-ver-tag s1)
                     :chunks [{:type :sack
                               :cum-tsn-ack (:remote-tsn s1)
                               :a-rwnd 100000
                               :gap-blocks []
                               :duplicate-tsns []}]}
        out [sack-packet]]
    (cond
      (= proto :webrtc/dcep)
      (let [payload (:payload chunk)
            msg-type (bit-and (aget ^bytes payload 0) 0xff)]
        (if (= msg-type 3) ;; OPEN
          (let [ack-tsn (:next-tsn s1)
                s2 (update s1 :next-tsn inc)
                ack-ssn (:ssn s2)
                s3 (update s2 :ssn inc)
                ack-packet {:src-port (:dst-port packet)
                            :dst-port (:src-port packet)
                            :verification-tag (:remote-ver-tag s3)
                            :chunks [{:type :data
                                      :flags 3 ;; B and E bits
                                      :tsn ack-tsn
                                      :stream-id (:stream-id chunk)
                                      :seq-num ack-ssn
                                      :protocol :webrtc/dcep
                                      :payload (byte-array [(byte 2)])}]}]
            {:next-state s3 :next-out (conj out ack-packet) :next-events []})
          {:next-state s1 :next-out out :next-events []}))
      :else
      {:next-state s1 :next-out out :next-events [{:type :on-message :payload (:payload chunk) :stream-id (:stream-id chunk) :protocol proto}]})))

(defmethod process-chunk :init [state chunk packet now-ms]
  (let [s1 (-> state
               (assoc :remote-ver-tag (:init-tag chunk)
                      :remote-tsn (dec (:initial-tsn chunk))
                      :ssn 0
                      :state :cookie-wait)
               (update :tx-queue (fn [q] (mapv (fn [item] (update item :packet assoc :verification-tag (:init-tag chunk))) q))))
        cookie-bytes (let [b (byte-array 32)] (.nextBytes secure-rand b) b)
        init-ack {:type :init-ack
                  :init-tag (:local-ver-tag s1)
                  :a-rwnd 100000
                  :outbound-streams (:inbound-streams chunk)
                  :inbound-streams (:outbound-streams chunk)
                  :initial-tsn (:next-tsn s1)
                  :params {:cookie cookie-bytes}}
        out-packet {:src-port (:dst-port packet)
                    :dst-port (:src-port packet)
                    :verification-tag (:init-tag chunk)
                    :chunks [init-ack]}]
    {:next-state s1 :next-out [out-packet] :next-events []}))

(defmethod process-chunk :init-ack [state chunk packet now-ms]
  (let [s1 (assoc state :remote-ver-tag (:init-tag chunk)
                        :remote-tsn (dec (:initial-tsn chunk)))]
    (if-let [cookie (get-in chunk [:params :cookie])]
      (let [out-packet {:src-port (:dst-port packet)
                        :dst-port (:src-port packet)
                        :verification-tag (:init-tag chunk)
                        :chunks [{:type :cookie-echo :cookie cookie}]}
            s2 (-> s1
                   (assoc :state :cookie-echoed)
                   (assoc-in [:timers :t1-init] {:expires-at (+ now-ms 3000)
                                                 :delay 3000
                                                 :retries 0
                                                 :packet out-packet}))]
        {:next-state s2 :next-out [out-packet] :next-events []})
      {:next-state s1 :next-out [] :next-events []})))

(defmethod process-chunk :cookie-echo [state chunk packet now-ms]
  (let [interval (get state :heartbeat-interval 30000)
        s1 (assoc state :state :established)
        s2 (if (pos? interval)
             (assoc-in s1 [:timers :t-heartbeat] {:expires-at (+ now-ms interval)})
             s1)
        out-packet {:src-port (:dst-port packet)
                    :dst-port (:src-port packet)
                    :verification-tag (:remote-ver-tag s2)
                    :chunks [{:type :cookie-ack}]}
        tx-queue (get s2 :tx-queue [])
        new-out [out-packet]
        [s3 final-out] (if (and (contains? #{:cookie-wait :cookie-echoed} (:state state)) (= (:state s2) :established) (seq tx-queue))
                         (let [pkts (map :packet tx-queue)]
                           [(assoc-in s2 [:timers :t3-rtx] {:expires-at (+ now-ms 1000) :delay 1000})
                            (into new-out pkts)])
                         [s2 new-out])
        tx-pkts (count tx-queue)
        tx-bytes (reduce + (map (fn [item]
                                  (reduce + (map #(alength ^bytes (:payload %))
                                                 (filter #(= (:type %) :data) (:chunks (:packet item))))))
                                tx-queue))
        s4 (if (and (contains? #{:cookie-wait :cookie-echoed} (:state state)) (= (:state s2) :established) (seq tx-queue))
             (-> s3
                 (update-in [:metrics :tx-packets] (fnil + 0) tx-pkts)
                 (update-in [:metrics :tx-bytes] (fnil + 0) tx-bytes))
             s3)]
    {:next-state s4 :next-out final-out :next-events [{:type :on-open}]}))

(defmethod process-chunk :cookie-ack [state chunk packet now-ms]
  (let [interval (get state :heartbeat-interval 30000)
        s1 (update state :timers dissoc :t1-init)
        s2 (if (pos? interval)
             (assoc-in s1 [:timers :t-heartbeat] {:expires-at (+ now-ms interval)})
             s1)
        [s3 out-packet] (if (= (:state s2) :shutdown-pending)
                          (let [s-shut (assoc s2 :state :shutdown-sent)
                                p {:src-port (:dst-port packet)
                                   :dst-port (:src-port packet)
                                   :verification-tag (:remote-ver-tag s-shut)
                                   :chunks [{:type :shutdown}]}
                                s-shut-t (assoc-in s-shut [:timers :t2-shutdown]
                                                   {:expires-at (+ now-ms 3000)
                                                    :delay 3000
                                                    :retries 0
                                                    :packet p})]
                            [s-shut-t p])
                          [s2 nil])
        s4 (if-not (= (:state s3) :shutdown-sent)
             (assoc s3 :state :established)
             s3)
        tx-queue (get s4 :tx-queue [])
        new-out (if out-packet [out-packet] [])
        [s5 final-out] (if (and (contains? #{:cookie-wait :cookie-echoed} (:state state)) (= (:state s4) :established) (seq tx-queue))
                         (let [pkts (map :packet tx-queue)]
                           [(assoc-in s4 [:timers :t3-rtx] {:expires-at (+ now-ms 1000) :delay 1000})
                            (into new-out pkts)])
                         [s4 new-out])
        tx-pkts (count tx-queue)
        tx-bytes (reduce + (map (fn [item]
                                  (reduce + (map #(alength ^bytes (:payload %))
                                                 (filter #(= (:type %) :data) (:chunks (:packet item))))))
                                tx-queue))
        s6 (if (and (contains? #{:cookie-wait :cookie-echoed} (:state state)) (= (:state s4) :established) (seq tx-queue))
             (-> s5
                 (update-in [:metrics :tx-packets] (fnil + 0) tx-pkts)
                 (update-in [:metrics :tx-bytes] (fnil + 0) tx-bytes))
             s5)]
    {:next-state s6 :next-out final-out :next-events [{:type :on-open}]}))

(defmethod process-chunk :heartbeat [state chunk packet now-ms]
  (let [out-packet {:src-port (:dst-port packet)
                    :dst-port (:src-port packet)
                    :verification-tag (:verification-tag packet)
                    :chunks [{:type :heartbeat-ack :params (:params chunk)}]}]
    {:next-state state :next-out [out-packet] :next-events []}))

(defmethod process-chunk :sack [state chunk packet now-ms]
  (let [cum-tsn-ack (:cum-tsn-ack chunk)
        q (get state :tx-queue [])
        new-q (vec (remove (fn [{:keys [tsn]}]
                             (not (pos? (unchecked-int (unchecked-subtract (unchecked-int tsn) (unchecked-int cum-tsn-ack))))))
                           q))
        s1 (if (empty? new-q)
             (-> state
                 (assoc :tx-queue new-q)
                 (assoc :heartbeat-error-count 0)
                 (assoc-in [:metrics :unacked-data] (count new-q))
                 (update :timers dissoc :t3-rtx))
             (-> state
                 (assoc :tx-queue new-q)
                 (assoc-in [:metrics :unacked-data] (count new-q))
                 (assoc :heartbeat-error-count 0)))]
    {:next-state s1 :next-out [] :next-events []}))

(defmethod process-chunk :heartbeat-ack [state chunk packet now-ms]
  (let [s1 (-> state
               (assoc :heartbeat-error-count 0)
               (update :timers dissoc :t-heartbeat-rtx))]
    {:next-state s1 :next-out [] :next-events []}))

(defmethod process-chunk :shutdown [state chunk packet now-ms]
  (let [s1 (assoc state :state :shutdown-ack-sent)
        out-packet {:src-port (:dst-port packet)
                    :dst-port (:src-port packet)
                    :verification-tag (:remote-ver-tag s1)
                    :chunks [{:type :shutdown-ack}]}
        s2 (assoc-in s1 [:timers :t2-shutdown]
                     {:expires-at (+ now-ms 3000)
                      :delay 3000
                      :retries 0
                      :packet out-packet})]
    {:next-state s2 :next-out [out-packet] :next-events []}))

(defmethod process-chunk :shutdown-ack [state chunk packet now-ms]
  (let [s1 (update state :timers dissoc :t2-shutdown)
        s2 (assoc s1 :state :closed)
        out-packet {:src-port (:dst-port packet)
                    :dst-port (:src-port packet)
                    :verification-tag (:remote-ver-tag s2)
                    :chunks [{:type :shutdown-complete}]}]
    {:next-state s2 :next-out [out-packet] :next-events []}))

(defmethod process-chunk :shutdown-complete [state chunk packet now-ms]
  (let [s1 (update state :timers dissoc :t2-shutdown)
        s2 (assoc s1 :state :closed)]
    {:next-state s2 :next-out [] :next-events [{:type :on-close}]}))

(defmethod process-chunk :error [state chunk packet now-ms]
  {:next-state state :next-out [] :next-events [{:type :on-error :causes (:causes chunk)}]})

(defmethod process-chunk :abort [state chunk packet now-ms]
  {:next-state state :next-out [] :next-events [{:type :on-close}]})

(defmethod process-chunk :default [state chunk packet now-ms]
  (let [type-val (:type chunk)]
    (if (number? type-val)
      (let [upper-bits (bit-shift-right (bit-and type-val 0xC0) 6)]
        (cond
          (= upper-bits 1)
          (let [out-packet {:src-port (:dst-port packet)
                            :dst-port (:src-port packet)
                            :verification-tag (:remote-ver-tag state)
                            :chunks [{:type :error
                                      :causes [{:cause-code 6
                                                :chunk-data (:body chunk)}]}]}]
            {:next-state state :next-out [out-packet] :next-events []})
          :else
          {:next-state state :next-out [] :next-events []}))
      {:next-state state :next-out [] :next-events []})))


(defn handle-sctp-packet [state packet now-ms]
  (let [chunks (:chunks packet)
        state-with-rx (update-in state [:metrics :rx-packets] (fnil inc 0))]
    (loop [current-state state-with-rx
           remaining-chunks chunks
           network-out []
           app-events []]
      (if (empty? remaining-chunks)
        {:new-state (update-in current-state [:metrics :tx-packets] (fnil + 0) (count network-out))
         :network-out network-out
         :app-events app-events}
        (let [chunk (first remaining-chunks)
              {:keys [next-state next-out next-events]}
              (process-chunk current-state chunk packet now-ms)]
          (recur next-state
                 (rest remaining-chunks)
                 (into network-out next-out)
                 (into app-events next-events)))))))


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
                                  :metrics {:tx-packets 0
                                            :rx-packets 0
                                            :tx-bytes   0
                                            :rx-bytes   0
                                            :retransmissions 0
                                            :unacked-data 0
                                            :uses-zero-checksum (boolean (:zero-checksum? options))}})
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
          tsn (or (:next-tsn state) 0)
          ssn (or (:ssn state) 0)
          packet {:src-port 5000
                  :dst-port 5000
                  :verification-tag ver-tag
                  :chunks [{:type :data
                            :flags 3 ;; B and E bits
                            :tsn tsn
                            :stream-id stream-id
                            :seq-num ssn
                            :protocol protocol
                            :payload payload}]}
          is-established? (= (:state state) :established)
          s1 (-> state
                 (assoc :next-tsn (inc tsn) :ssn (inc ssn)))
          q (get s1 :tx-queue [])
          new-q (conj q {:tsn tsn :packet packet :sent-at now-ms :retries 0})
          s2 (assoc s1 :tx-queue new-q)
          interval (get s2 :heartbeat-interval 30000)
          s3 (if (and (pos? interval) (contains? (:timers s2) :t-heartbeat))
               (assoc-in s2 [:timers :t-heartbeat] {:expires-at (+ now-ms interval)})
               s2)
          s4 (-> s3
                 (update-in [:metrics :unacked-data] (fnil inc 0))
                 (cond-> is-established?
                   (-> (update-in [:metrics :tx-packets] (fnil inc 0))
                       (update-in [:metrics :tx-bytes] (fnil + 0) len))))
          s5 (if (and is-established? (nil? (get-in s4 [:timers :t3-rtx])))
               (assoc-in s4 [:timers :t3-rtx] {:expires-at (+ now-ms 1000) :delay 1000})
               s4)]
      {:new-state s5
       :network-out (if is-established? [packet] [])
       :app-events []})))

(defn close [connection]
  (close-channel (:channel connection))
  (close-selector (:selector connection)))
