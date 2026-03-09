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
              current-flight-size]}
      {:action :return
       :result {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
                :network-out (build-network-out state bundled-chunks)
                :app-events app-events}})))

(defn- process-stream-data
  [remaining-ctrl current-streams bundled-chunks current-size current-flight-size max-payload-size state app-events]
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
            chunk-size (+ 16 (if (:payload data-chunk) (alength ^bytes (:payload data-chunk)) 0))
            cwnd (get state :cwnd 1000000)]
        (if (and (> current-flight-size 0) (> (+ current-flight-size chunk-size) cwnd))
          ;; Congestion window full
          {:action :return
           :result {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
                    :network-out (build-network-out state bundled-chunks)
                    :app-events app-events}}
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
                      new-flight-size]})
            ;; Halting when MTU is reached
            {:action :return
             :result {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
                      :network-out (build-network-out state bundled-chunks)
                      :app-events app-events}})))
      ;; No more data in streams
      {:action :return
       :result {:new-state (assoc state :pending-control-chunks [] :streams current-streams :flight-size current-flight-size)
                :network-out (build-network-out state bundled-chunks)
                :app-events app-events}})))

(defn packetize [state app-events]
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
             current-flight-size (get state :flight-size 0)]
        (let [step-result (if (seq remaining-ctrl)
                            (process-control-chunks remaining-ctrl current-streams bundled-chunks current-size current-flight-size max-payload-size state app-events)
                            (process-stream-data remaining-ctrl current-streams bundled-chunks current-size current-flight-size max-payload-size state app-events))]
          (if (= (:action step-result) :recur)
            (let [[args-rem-ctrl args-cur-streams args-bundled args-cur-size args-cur-flight] (:args step-result)]
              (recur args-rem-ctrl args-cur-streams args-bundled args-cur-size args-cur-flight))
            (:result step-result)))))))