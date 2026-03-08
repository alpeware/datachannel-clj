(ns patch-step6
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-handle-timeout-t3 [content]
  (let [search-str "              :t3-rtx
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
                         :app-events []})))))"
        replace-str "              :t3-rtx
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
                                        (assoc-in [:timers :t3-rtx] {:expires-at (+ now-ms new-delay) :delay new-delay})
                                        (update-in [:metrics :retransmissions] (fnil inc 0))
                                        (assoc-in [:streams stream-id :send-queue] new-q)
                                        (assoc :ssthresh new-ssthresh)
                                        (assoc :cwnd new-cwnd)
                                        (assoc :flight-size flight-size))
                         :app-events []})))))"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find handle-timeout :t3-rtx to patch.")
        content))))

(defn apply-patch []
  (let [c6 (patch-handle-timeout-t3 core-content)]
    (spit core-path c6)))

(apply-patch)
