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

(defn packetize [state app-events]
  (let [mtu (get state :mtu 1200)
        ;; Always leave room for SCTP common header (12 bytes)
        max-payload-size (- mtu 12)
        pending-ctrl (:pending-control-chunks state)
        streams (:streams state)]

    (if (and (empty? pending-ctrl) (every? empty? (map :send-queue (vals streams))))
      {:new-state state :network-out [] :app-events app-events}

      (loop [remaining-ctrl pending-ctrl
             current-streams streams
             bundled-chunks []
             current-size 0]
        (if (seq remaining-ctrl)
          (let [chunk (first remaining-ctrl)
                ;; Approximate size, could be more precise but assuming small for control chunks
                chunk-size 16]
            (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
              (recur (rest remaining-ctrl)
                     current-streams
                     (conj bundled-chunks chunk)
                     (+ current-size chunk-size))
              ;; Stop if it exceeds MTU (unlikely for control chunks but adhering to strict boundary)
              {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams)
               :network-out [{:src-port (get state :local-port 5000)
                              :dst-port (get state :remote-port 5000)
                              :verification-tag (get state :remote-ver-tag 0)
                              :chunks bundled-chunks}]
               :app-events app-events}))

          ;; Pull from streams
          ;; Basic round robin or just pulling from first available stream
          ;; We only pull items that haven't been sent yet
          (let [active-stream-entry (first (filter #(some (fn [item] (not (:sent? item))) (:send-queue (val %))) current-streams))]
            (if active-stream-entry
              (let [[stream-id stream-data] active-stream-entry
                    q (:send-queue stream-data)
                    data-idx (first (keep-indexed #(when-not (:sent? %2) %1) q))
                    data-item (nth q data-idx)
                    data-chunk (:chunk data-item)
                    ;; Approximate size, +16 for chunk header overhead
                    chunk-size (+ 16 (if (:payload data-chunk) (alength ^bytes (:payload data-chunk)) 0))]

                (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
                  (let [new-q (assoc q data-idx (assoc data-item :sent? true))
                        new-streams (assoc-in current-streams [stream-id :send-queue] new-q)]
                    (recur remaining-ctrl
                           new-streams
                           (conj bundled-chunks data-chunk)
                           (+ current-size chunk-size)))

                  ;; Halting when MTU is reached
                  {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams)
                   :network-out [{:src-port (get state :local-port 5000)
                                  :dst-port (get state :remote-port 5000)
                                  :verification-tag (get state :remote-ver-tag 0)
                                  :chunks bundled-chunks}]
                   :app-events app-events}))

              ;; No more data in streams
              {:new-state (assoc state :pending-control-chunks [] :streams current-streams)
               :network-out (if (seq bundled-chunks)
                              [{:src-port (get state :local-port 5000)
                                :dst-port (get state :remote-port 5000)
                                :verification-tag (get state :remote-ver-tag 0)
                                :chunks bundled-chunks}]
                              [])
               :app-events app-events})))))))

(defn handle-event [state event now-ms]
  (let [res (case (:type event)
              :connect
              (let [{:keys [local-ver-tag initial-tsn]} state
                    init-chunk {:type :init
                                :init-tag local-ver-tag
                                :a-rwnd 100000
                                :outbound-streams 10
                                :inbound-streams 10
                                :initial-tsn initial-tsn
                                :params {}}
                    init-packet {:src-port 5000
                                 :dst-port 5000
                                 :verification-tag 0
                                 :chunks [init-chunk]}]
                {:new-state (-> state
                                (assoc :state :cookie-wait)
                                (assoc-in [:timers :t1-init] {:expires-at (+ now-ms 3000) :delay 3000 :retries 0 :packet init-packet})
                                (update :pending-control-chunks conj init-chunk)
                                (update-in [:metrics :tx-packets] (fnil inc 0)))
                 :app-events []})

              :shutdown
              (if (= (:state state) :established)
                (if (every? empty? (map :send-queue (vals (:streams state))))
                  (let [shutdown-chunk {:type :shutdown}
                        shutdown-packet {:src-port (get state :local-port 5000)
                                         :dst-port (get state :remote-port 5000)
                                         :verification-tag (:remote-ver-tag state)
                                         :chunks [shutdown-chunk]}
                        new-state (-> state
                                      (assoc :state :shutdown-sent)
                                      (assoc-in [:timers :t2-shutdown] {:expires-at (+ now-ms 3000) :delay 3000 :retries 0 :packet shutdown-packet})
                                      (update :pending-control-chunks conj shutdown-chunk))]
                    {:new-state new-state :app-events []})
                  {:new-state (assoc state :state :shutdown-pending) :app-events []})
                {:new-state state :app-events []})

              {:new-state state :app-events []})]
    (packetize (:new-state res) (:app-events res))))

(defn handle-timeout [state timer-id now-ms]
  (let [res (case timer-id
              :t2-shutdown
              (let [timer (get-in state [:timers :t2-shutdown])]
                (if-not timer
                  {:new-state state :app-events []}
                  (let [retries (:retries timer)]
                    (if (>= retries 8)
                      (let [abort-chunk {:type :abort}]
                        {:new-state (-> state
                                        (assoc :state :closed)
                                        (update :timers dissoc :t2-shutdown)
                                        (update :pending-control-chunks conj abort-chunk))
                         :app-events [{:type :on-error :cause :max-retransmissions}]})
                      (let [new-delay (* (:delay timer) 2)
                            new-delay (min new-delay 60000)
                            packet (:packet timer)]
                        {:new-state (-> state
                                        (assoc-in [:timers :t2-shutdown]
                                                  {:expires-at (+ now-ms new-delay)
                                                   :delay new-delay
                                                   :retries (inc retries)
                                                   :packet packet})
                                        (update :pending-control-chunks into (:chunks packet)))
                         :app-events []})))))

              :t1-init
              (let [timer (get-in state [:timers :t1-init])
                    retries (:retries timer)]
                (if (>= retries 8)
                  {:new-state (-> state
                                  (assoc :state :closed)
                                  (update :timers dissoc :t1-init))
                   :app-events [{:type :on-error :cause :max-retransmissions}]}
                  (let [new-delay (* (:delay timer) 2)
                        new-delay (min new-delay 60000) ;; Cap delay
                        packet (:packet timer)]
                    {:new-state (-> state
                                    (assoc-in [:timers :t1-init]
                                              {:expires-at (+ now-ms new-delay)
                                               :delay new-delay
                                               :retries (inc retries)
                                               :packet packet})
                                    (update :pending-control-chunks into (:chunks packet)))
                     :app-events []})))

              :t1-cookie
              (let [timer (get-in state [:timers :t1-cookie])
                    retries (:retries timer)]
                (if (>= retries 8)
                  {:new-state (-> state
                                  (assoc :state :closed)
                                  (update :timers dissoc :t1-cookie))
                   :app-events [{:type :on-error :cause :max-retransmissions}]}
                  (let [new-delay (* (:delay timer) 2)
                        new-delay (min new-delay 60000) ;; Cap delay
                        packet (:packet timer)]
                    {:new-state (-> state
                                    (assoc-in [:timers :t1-cookie]
                                              {:expires-at (+ now-ms new-delay)
                                               :delay new-delay
                                               :retries (inc retries)
                                               :packet packet})
                                    (update :pending-control-chunks into (:chunks packet)))
                     :app-events []})))

              :t3-rtx
              (let [timer (get-in state [:timers :t3-rtx])
                    active-streams (filter #(seq (:send-queue (val %))) (:streams state))]
                (if (empty? active-streams)
                  ;; If queue is empty, stop the timer
                  {:new-state (update state :timers dissoc :t3-rtx) :app-events []}
                  (let [[stream-id stream-data] (first active-streams)
                        q (:send-queue stream-data)
                        first-item (first q)
                        retries (:retries first-item)
                        max-retries (get state :max-retransmissions 10)]
                    (if (>= retries max-retries)
                      (let [abort-chunk {:type :abort}]
                        {:new-state (-> state
                                        (assoc :state :closed)
                                        (update :timers dissoc :t3-rtx)
                                        (update :pending-control-chunks conj abort-chunk))
                         :app-events [{:type :on-error :cause :max-retransmissions}]})
                      (let [new-delay (* (:delay timer) 2)
                            new-delay (min new-delay 60000)
                            ;; Mark sent? false so packetize will re-transmit it
                            updated-item (assoc first-item :retries (inc retries) :sent? false)
                            new-q (assoc q 0 updated-item)]
                        {:new-state (-> state
                                        (assoc-in [:timers :t3-rtx] {:expires-at (+ now-ms new-delay) :delay new-delay})
                                        (update-in [:metrics :retransmissions] (fnil inc 0))
                                        (assoc-in [:streams stream-id :send-queue] new-q))
                         :app-events []})))))

              :t-heartbeat
              (let [interval (get state :heartbeat-interval 30000)
                    rto (get state :rto-initial 1000)
                    hb-chunk {:type :heartbeat
                              :params [{:type :heartbeat-info :info (byte-array 8)}]}]
                {:new-state (-> state
                                (assoc-in [:timers :t-heartbeat] {:expires-at (+ now-ms interval)})
                                (assoc-in [:timers :t-heartbeat-rtx] {:expires-at (+ now-ms rto)})
                                (update :pending-control-chunks conj hb-chunk))
                 :app-events []})

              :t-heartbeat-rtx
              (let [errors (get state :heartbeat-error-count 0)
                    max-retries (get state :max-retransmissions 10)
                    new-errors (inc errors)]
                (if (> new-errors max-retries)
                  (let [abort-chunk {:type :abort}]
                    {:new-state (-> state
                                    (assoc :state :closed)
                                    (update :timers dissoc :t-heartbeat :t-heartbeat-rtx)
                                    (update :pending-control-chunks conj abort-chunk))
                     :app-events [{:type :on-error :cause :max-retransmissions}]})
                  {:new-state (-> state
                                  (assoc :heartbeat-error-count new-errors)
                                  (update :timers dissoc :t-heartbeat-rtx))
                   :app-events []}))

              {:new-state state :app-events []})]
    (packetize (:new-state res) (:app-events res))))


(defn handle-receive [state network-bytes now-ms]
  {:new-state state :network-out [] :app-events []})

(defmulti process-chunk (fn [state chunk packet now-ms] (:type chunk)))

(defmethod process-chunk :data [state chunk packet now-ms]
  (let [proto (:protocol chunk)
        tsn (:tsn chunk)
        s1 (if (pos? (unchecked-int (unchecked-subtract (unchecked-int tsn) (unchecked-int (get state :remote-tsn -1)))))
             (assoc state :remote-tsn tsn)
             state)
        sack-chunk {:type :sack
                    :cum-tsn-ack (:remote-tsn s1)
                    :a-rwnd 100000
                    :gap-blocks []
                    :duplicate-tsns []}
        s1 (update s1 :pending-control-chunks conj sack-chunk)]
    (cond
      (= proto :webrtc/dcep)
      (let [payload (:payload chunk)
            msg-type (bit-and (aget ^bytes payload 0) 0xff)]
        (if (= msg-type 3) ;; OPEN
          (let [ack-tsn (:next-tsn s1)
                s2 (update s1 :next-tsn inc)
                ack-ssn (:ssn s2)
                s3 (update s2 :ssn inc)
                ack-chunk {:type :data
                           :flags 3 ;; B and E bits
                           :tsn ack-tsn
                           :stream-id (:stream-id chunk)
                           :seq-num ack-ssn
                           :protocol :webrtc/dcep
                           :payload (byte-array [(byte 2)])}]
            ;; For now, putting ack chunk directly into streams
            {:next-state (assoc-in s3 [:streams (:stream-id chunk) :send-queue]
                                   (conj (get-in s3 [:streams (:stream-id chunk) :send-queue] [])
                                         {:tsn ack-tsn :chunk ack-chunk :sent-at now-ms :retries 0 :sent? false}))
             :next-events []})
          {:next-state s1 :next-events []}))
      :else
      {:next-state s1 :next-events [{:type :on-message :payload (:payload chunk) :stream-id (:stream-id chunk) :protocol proto}]})))

(defmethod process-chunk :init [state chunk packet now-ms]
  (let [s1 (-> state
               (assoc :remote-ver-tag (:init-tag chunk)
                      :remote-tsn (dec (:initial-tsn chunk))
                      :ssn 0
                      :state :cookie-wait))
        cookie-bytes (let [b (byte-array 32)] (.nextBytes secure-rand b) b)
        init-ack-chunk {:type :init-ack
                        :init-tag (:local-ver-tag s1)
                        :a-rwnd 100000
                        :outbound-streams (:inbound-streams chunk)
                        :inbound-streams (:outbound-streams chunk)
                        :initial-tsn (:next-tsn s1)
                        :params {:cookie cookie-bytes}}
        s2 (update s1 :pending-control-chunks conj init-ack-chunk)]
    {:next-state s2 :next-events []}))

(defmethod process-chunk :init-ack [state chunk packet now-ms]
  (let [s1 (assoc state :remote-ver-tag (:init-tag chunk)
                        :remote-tsn (dec (:initial-tsn chunk)))]
    (if-let [cookie (get-in chunk [:params :cookie])]
      (let [cookie-echo-chunk {:type :cookie-echo :cookie cookie}
            out-packet {:src-port (:dst-port packet)
                        :dst-port (:src-port packet)
                        :verification-tag (:init-tag chunk)
                        :chunks [cookie-echo-chunk]}
            s2 (-> s1
                   (assoc :state :cookie-echoed)
                   (update :timers dissoc :t1-init)
                   (assoc-in [:timers :t1-cookie] {:expires-at (+ now-ms 3000)
                                                   :delay 3000
                                                   :retries 0
                                                   :packet out-packet})
                   (update :pending-control-chunks conj cookie-echo-chunk))]
        {:next-state s2 :next-events []})
      (let [s2 (assoc state :state :closed)
            abort-chunk {:type :abort}
            s3 (update s2 :pending-control-chunks conj abort-chunk)]
        {:next-state s3 :next-events [{:type :on-error :cause :protocol-violation}]}))))

(defmethod process-chunk :cookie-echo [state chunk packet now-ms]
  (let [interval (get state :heartbeat-interval 30000)
        s1 (-> state
               (assoc :state :established)
               (update :timers dissoc :t1-init)
               (update :timers dissoc :t1-cookie))
        s2 (if (pos? interval)
             (assoc-in s1 [:timers :t-heartbeat] {:expires-at (+ now-ms interval)})
             s1)
        cookie-ack-chunk {:type :cookie-ack}
        s3 (update s2 :pending-control-chunks conj cookie-ack-chunk)
        has-buffered-data? (some #(seq (:send-queue (val %))) (:streams s3))
        s4 (if (and (contains? #{:cookie-wait :cookie-echoed} (:state state)) (= (:state s3) :established) has-buffered-data?)
             (assoc-in s3 [:timers :t3-rtx] {:expires-at (+ now-ms 1000) :delay 1000})
             s3)
        tx-pkts (reduce + (map #(count (:send-queue (val %))) (:streams s4)))
        tx-bytes (reduce + (map (fn [st]
                                  (reduce + (map (fn [item]
                                                   (let [dc (:chunk item)]
                                                     (if (:payload dc) (alength ^bytes (:payload dc)) 0)))
                                                 (:send-queue (val st)))))
                                (:streams s4)))
        s5 (if (and (contains? #{:cookie-wait :cookie-echoed} (:state state)) (= (:state s3) :established) has-buffered-data?)
             (-> s4
                 (update-in [:metrics :tx-packets] (fnil + 0) tx-pkts)
                 (update-in [:metrics :tx-bytes] (fnil + 0) tx-bytes))
             s4)]
    {:next-state s5 :next-events [{:type :on-open}]}))

(defmethod process-chunk :cookie-ack [state chunk packet now-ms]
  (let [interval (get state :heartbeat-interval 30000)
        s1 (-> state
               (update :timers dissoc :t1-init)
               (update :timers dissoc :t1-cookie))
        s2 (if (pos? interval)
             (assoc-in s1 [:timers :t-heartbeat] {:expires-at (+ now-ms interval)})
             s1)
        s3 (if (= (:state s2) :shutdown-pending)
             (let [s-shut (assoc s2 :state :shutdown-sent)
                   shutdown-chunk {:type :shutdown}
                   p {:src-port (:dst-port packet)
                      :dst-port (:src-port packet)
                      :verification-tag (:remote-ver-tag s-shut)
                      :chunks [shutdown-chunk]}
                   s-shut-t (assoc-in s-shut [:timers :t2-shutdown]
                                      {:expires-at (+ now-ms 3000)
                                       :delay 3000
                                       :retries 0
                                       :packet p})]
               (update s-shut-t :pending-control-chunks conj shutdown-chunk))
             s2)
        s4 (if-not (= (:state s3) :shutdown-sent)
             (assoc s3 :state :established)
             s3)
        has-buffered-data? (some #(seq (:send-queue (val %))) (:streams s4))
        s5 (if (and (contains? #{:cookie-wait :cookie-echoed} (:state state)) (= (:state s4) :established) has-buffered-data?)
             (assoc-in s4 [:timers :t3-rtx] {:expires-at (+ now-ms 1000) :delay 1000})
             s4)
        tx-pkts (reduce + (map #(count (:send-queue (val %))) (:streams s5)))
        tx-bytes (reduce + (map (fn [st]
                                  (reduce + (map (fn [item]
                                                   (let [dc (:chunk item)]
                                                     (if (:payload dc) (alength ^bytes (:payload dc)) 0)))
                                                 (:send-queue (val st)))))
                                (:streams s5)))
        s6 (if (and (contains? #{:cookie-wait :cookie-echoed} (:state state)) (= (:state s4) :established) has-buffered-data?)
             (-> s5
                 (update-in [:metrics :tx-packets] (fnil + 0) tx-pkts)
                 (update-in [:metrics :tx-bytes] (fnil + 0) tx-bytes))
             s5)]
    {:next-state s6 :next-events [{:type :on-open}]}))

(defmethod process-chunk :heartbeat [state chunk packet now-ms]
  (let [heartbeat-ack-chunk {:type :heartbeat-ack :params (:params chunk)}
        s1 (update state :pending-control-chunks conj heartbeat-ack-chunk)]
    {:next-state s1 :next-events []}))

(defmethod process-chunk :sack [state chunk packet now-ms]
  (let [cum-tsn-ack (:cum-tsn-ack chunk)
        streams (:streams state)
        new-streams (reduce-kv
                     (fn [m k v]
                       (let [q (:send-queue v)
                             new-q (vec (remove (fn [{:keys [tsn]}]
                                                  (not (pos? (unchecked-int (unchecked-subtract (unchecked-int tsn) (unchecked-int cum-tsn-ack))))))
                                                q))]
                         (if (empty? new-q)
                           (assoc m k (dissoc v :send-queue))
                           (assoc m k (assoc v :send-queue new-q)))))
                     {}
                     streams)
        all-empty? (every? #(empty? (:send-queue (val %))) new-streams)
        total-unacked (reduce + (map #(count (:send-queue (val %))) new-streams))
        s1 (if all-empty?
             (-> state
                 (assoc :streams new-streams)
                 (assoc :heartbeat-error-count 0)
                 (assoc-in [:metrics :unacked-data] total-unacked)
                 (update :timers dissoc :t3-rtx))
             (-> state
                 (assoc :streams new-streams)
                 (assoc-in [:metrics :unacked-data] total-unacked)
                 (assoc :heartbeat-error-count 0)))]
    {:next-state s1 :next-events []}))

(defmethod process-chunk :heartbeat-ack [state chunk packet now-ms]
  (let [s1 (-> state
               (assoc :heartbeat-error-count 0)
               (update :timers dissoc :t-heartbeat-rtx))]
    {:next-state s1 :next-events []}))

(defmethod process-chunk :shutdown [state chunk packet now-ms]
  (let [s1 (assoc state :state :shutdown-ack-sent)
        shutdown-ack-chunk {:type :shutdown-ack}
        out-packet {:src-port (:dst-port packet)
                    :dst-port (:src-port packet)
                    :verification-tag (:remote-ver-tag s1)
                    :chunks [shutdown-ack-chunk]}
        s2 (-> s1
               (assoc-in [:timers :t2-shutdown]
                         {:expires-at (+ now-ms 3000)
                          :delay 3000
                          :retries 0
                          :packet out-packet})
               (update :pending-control-chunks conj shutdown-ack-chunk))]
    {:next-state s2 :next-events []}))

(defmethod process-chunk :shutdown-ack [state chunk packet now-ms]
  (let [s1 (update state :timers dissoc :t2-shutdown)
        s2 (assoc s1 :state :closed)
        shutdown-complete-chunk {:type :shutdown-complete}
        s3 (update s2 :pending-control-chunks conj shutdown-complete-chunk)]
    {:next-state s3 :next-events []}))

(defmethod process-chunk :shutdown-complete [state chunk packet now-ms]
  (let [s1 (update state :timers dissoc :t2-shutdown)
        s2 (assoc s1 :state :closed)]
    {:next-state s2 :next-events [{:type :on-close}]}))

(defmethod process-chunk :error [state chunk packet now-ms]
  {:next-state state :next-events [{:type :on-error :causes (:causes chunk)}]})

(defmethod process-chunk :abort [state chunk packet now-ms]
  {:next-state state :next-events [{:type :on-close}]})

(defmethod process-chunk :default [state chunk packet now-ms]
  (let [type-val (:type chunk)]
    (if (number? type-val)
      (let [upper-bits (bit-shift-right (bit-and type-val 0xC0) 6)]
        (cond
          (= upper-bits 1)
          (let [error-chunk {:type :error
                             :causes [{:cause-code 6
                                       :chunk-data (:body chunk)}]}
                s1 (update state :pending-control-chunks conj error-chunk)]
            {:next-state s1 :next-events []})
          :else
          {:next-state state :next-events []}))
      {:next-state state :next-events []})))


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
                        (process-chunk current-state chunk packet now-ms)]
                    (recur next-state
                           (rest remaining-chunks)
                           (into app-events next-events)))))]
      (packetize (:new-state res) (:app-events res)))))


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
          data-chunk {:type :data
                      :flags 3 ;; B and E bits
                      :tsn tsn
                      :stream-id stream-id
                      :seq-num ssn
                      :protocol protocol
                      :payload payload}
          is-established? (= (:state state) :established)
          s1 (-> state
                 (assoc :next-tsn (inc tsn) :ssn (inc ssn)))
          queue-item {:tsn tsn :chunk data-chunk :sent-at now-ms :retries 0 :sent? false}
          s2 (assoc-in s1 [:streams stream-id :send-queue] (conj (get-in s1 [:streams stream-id :send-queue] []) queue-item))
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
               s4)
          ;; If not established, we just buffered it in streams.
          ;; Don't packetize network-out if not established
          res (if is-established?
                {:new-state s5 :app-events []}
                {:new-state s5 :app-events []})]
      (if is-established?
        (packetize (:new-state res) (:app-events res))
        {:new-state (:new-state res) :network-out [] :app-events []}))))

(defn close [connection]
  (close-channel (:channel connection))
  (close-selector (:selector connection)))
