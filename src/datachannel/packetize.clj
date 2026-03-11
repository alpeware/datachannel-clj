(ns datachannel.packetize)

(defn- build-network-out [state bundled-chunks]
  (if (seq bundled-chunks)
    [{:src-port (get state :local-port 5000)
      :dst-port (get state :remote-port 5000)
      :verification-tag (get state :remote-ver-tag 0)
      :chunks bundled-chunks}]
    []))

(defn- process-control-chunks
  [remaining-ctrl current-streams bundled-chunks current-size current-flight-size max-payload-size state app-events]
  (let [chunk (first remaining-ctrl)
        chunk-size 16] ;; Approximate size
    (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
      {:action :recur
       :args [(rest remaining-ctrl)
              current-streams
              (conj bundled-chunks chunk)
              (+ current-size chunk-size)
              current-flight-size
              app-events]}
      (let [net-out (build-network-out state bundled-chunks)
            new-state (-> state
                          (assoc :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
                          (cond-> (seq net-out) (update-in [:metrics :tx-packets] (fnil + 0) (count net-out))))]
        {:action :return
         :result {:new-state new-state
                  :network-out net-out
                  :app-events app-events}}))))

(defn- process-stream-data
  [remaining-ctrl current-streams bundled-chunks current-size current-flight-size max-payload-size state app-events now-ms]
  (let [can-send-data? (contains? #{:established :shutdown-pending :shutdown-sent} (:state state))
        active-stream-entry (if can-send-data?
                              (first (filter #(some (fn [item] (not (:sent? item))) (:send-queue (val %))) current-streams))
                              nil)]
    (if active-stream-entry
      (let [[stream-id stream-data] active-stream-entry
            q (:send-queue stream-data)
            data-idx (first (keep-indexed #(when-not (:sent? %2) %1) q))
            data-item (nth q data-idx)
            data-chunk (:chunk data-item)
            max-lifetime (:max-packet-life-time data-item)
            expired? (and max-lifetime (>= now-ms (+ (:sent-at data-item) max-lifetime)))]
        (if expired?
          ;; Drop this fragment (and any others in the same message) and send FORWARD-TSN
          (let [;; Find all chunks belonging to the same user message
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
                abandoned-bytes (reduce + (map #(if (:sent? %) (+ 16 (if (:payload (:chunk %)) (alength ^bytes (:payload (:chunk %))) 0)) 0) dropped-items))
                new-flight-size (max 0 (- current-flight-size abandoned-bytes))
                stream-id-to-fwd (get-in (first dropped-items) [:chunk :stream-id] stream-id)
                seq-num-to-fwd (get-in (first dropped-items) [:chunk :seq-num] 0)
                forward-tsn-chunk {:type :forward-tsn :new-cumulative-tsn last-dropped-tsn :streams [{:stream-id stream-id-to-fwd :stream-sequence seq-num-to-fwd}]}
                new-streams (assoc-in current-streams [stream-id :send-queue] new-q)
                ;; Insert FORWARD-TSN into pending-control-chunks
                new-pending-ctrl (conj remaining-ctrl forward-tsn-chunk)

                old-buffered (reduce + (map #(if-let [p (:payload (:chunk %))] (alength ^bytes p) 0) q))
                new-buffered (reduce + (map #(if-let [p (:payload (:chunk %))] (alength ^bytes p) 0) new-q))
                low-threshold (get state :buffered-amount-low-threshold 0)
                new-app-events (if (and (> old-buffered low-threshold) (<= new-buffered low-threshold))
                                 (conj app-events {:type :on-buffered-amount-low :stream-id stream-id})
                                 app-events)]
            ;; NOTE: we pass new-app-events somehow? No, process-stream-data doesn't return app-events if it recurs.
            ;; Wait, `packetize-step` doesn't pass `new-app-events` through `recur`. Oh.
            ;; Let's fix this properly. Oh wait, process-control-chunks also doesn't update app-events. Wait, `app-events` are not updated across recurs in `packetize-step`.
            {:action :recur
             :args [new-pending-ctrl
                    new-streams
                    bundled-chunks
                    current-size
                    new-flight-size
                    new-app-events]})
          (let [chunk-size (+ 16 (if (:payload data-chunk) (alength ^bytes (:payload data-chunk)) 0))
                cwnd (get state :cwnd 1000000)]
            (if (and (> current-flight-size 0) (> (+ current-flight-size chunk-size) cwnd))
              ;; Congestion window full
              (let [net-out (build-network-out state bundled-chunks)
                    new-state (-> state
                                  (assoc :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
                                  (cond-> (seq net-out) (update-in [:metrics :tx-packets] (fnil + 0) (count net-out))))]
                {:action :return
                 :result {:new-state new-state
                          :network-out net-out
                          :app-events app-events}})
              (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
                (let [new-q (assoc q data-idx (assoc data-item :sent? true))
                      new-streams (assoc-in current-streams [stream-id :send-queue] new-q)
                      new-flight-size (if (= (:retries data-item) 0)
                                        (+ current-flight-size chunk-size)
                                        current-flight-size)]
                  {:action :recur
                   :args [remaining-ctrl
                          new-streams
                          (conj bundled-chunks data-chunk)
                          (+ current-size chunk-size)
                          new-flight-size
                          app-events]})
                ;; Halting when MTU is reached
                (let [net-out (build-network-out state bundled-chunks)
                      new-state (-> state
                                    (assoc :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
                                    (cond-> (seq net-out) (update-in [:metrics :tx-packets] (fnil + 0) (count net-out))))]
                  {:action :return
                   :result {:new-state new-state
                            :network-out net-out
                            :app-events app-events}}))))))
      ;; No more data in streams
      (let [net-out (build-network-out state bundled-chunks)
            new-state (-> state
                          (assoc :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
                          (cond-> (seq net-out) (update-in [:metrics :tx-packets] (fnil + 0) (count net-out))))]
        {:action :return
         :result {:new-state new-state
                  :network-out net-out
                  :app-events app-events}}))))

(defn- packetize-step [state app-events now-ms]
  (let [mtu (get state :mtu 1200)
        max-payload-size (- mtu 12)
        pending-ctrl (:pending-control-chunks state)
        streams (:streams state)]

    (if (and (empty? pending-ctrl) (every? empty? (map :send-queue (vals streams))))
      {:new-state state :network-out [] :app-events app-events}

      (loop [remaining-ctrl pending-ctrl
             current-streams streams
             bundled-chunks []
             current-size 0
             current-flight-size (get state :flight-size 0)
             current-app-events app-events]
        (let [step-result (if (seq remaining-ctrl)
                            (process-control-chunks remaining-ctrl current-streams bundled-chunks current-size current-flight-size max-payload-size state current-app-events)
                            (process-stream-data remaining-ctrl current-streams bundled-chunks current-size current-flight-size max-payload-size state current-app-events now-ms))]
          (if (= (:action step-result) :recur)
            (let [[args-rem-ctrl args-cur-streams args-bundled args-cur-size args-cur-flight args-app-events] (:args step-result)]
              (recur args-rem-ctrl args-cur-streams args-bundled args-cur-size args-cur-flight args-app-events))
            (:result step-result)))))))

(defn packetize [state app-events now-ms]
  (let [max-burst (get state :max-burst 4)]
    (loop [current-state state
           all-pkts []
           current-events app-events
           data-pkts-count 0
           passes 0]
      (if (or (> passes 100) (>= data-pkts-count max-burst))
        {:new-state current-state :network-out all-pkts :app-events current-events}
        (let [step-res (packetize-step current-state current-events now-ms)
              pkts (:network-out step-res)]
          (if (empty? pkts)
            {:new-state current-state :network-out all-pkts :app-events current-events}
            (let [new-data-pkts (count (filter (fn [pkt] (some #(= (:type %) :data) (:chunks pkt))) pkts))]
              (if (and (> data-pkts-count 0) (> (+ data-pkts-count new-data-pkts) max-burst))
                {:new-state current-state :network-out all-pkts :app-events current-events}
                (recur (:new-state step-res) (into all-pkts pkts) (:app-events step-res) (+ data-pkts-count new-data-pkts) (inc passes))))))))))