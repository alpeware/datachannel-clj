(ns patch-step5
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-process-chunk-sack [content]
  (let [search-str "(defmethod process-chunk :sack [state chunk packet now-ms]
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
    {:next-state s1 :next-events []}))"
        replace-str "(defmethod process-chunk :sack [state chunk packet now-ms]
  (let [cum-tsn-ack (:cum-tsn-ack chunk)
        streams (:streams state)
        mtu (get state :mtu 1200)
        ;; Calculate newly acked bytes and reduce flight size
        ;; Also collect the new queues
        res (reduce-kv
             (fn [acc k v]
               (let [q (:send-queue v)
                     acked-items (filter (fn [{:keys [tsn sent?]}]
                                           (and sent? (not (pos? (unchecked-int (unchecked-subtract (unchecked-int tsn) (unchecked-int cum-tsn-ack)))))))
                                         q)
                     acked-bytes (reduce + (map #(let [payload (:payload (:chunk %))]
                                                   (+ 16 (if payload (alength ^bytes payload) 0))) acked-items))
                     new-q (vec (remove (fn [{:keys [tsn]}]
                                          (not (pos? (unchecked-int (unchecked-subtract (unchecked-int tsn) (unchecked-int cum-tsn-ack))))))
                                        q))]
                 (-> acc
                     (update :acked-bytes + acked-bytes)
                     (update :new-streams (fn [m]
                                            (if (empty? new-q)
                                              (assoc m k (dissoc v :send-queue))
                                              (assoc m k (assoc v :send-queue new-q))))))))
             {:acked-bytes 0 :new-streams {}}
             streams)
        acked-bytes (:acked-bytes res)
        new-streams (:new-streams res)
        flight-size (max 0 (- (get state :flight-size 0) acked-bytes))
        cwnd (get state :cwnd 0)
        ssthresh (get state :ssthresh 65535)

        ;; Congestion Control algorithm
        new-cwnd (if (> acked-bytes 0)
                   (if (<= cwnd ssthresh)
                     ;; Slow Start
                     (+ cwnd (min acked-bytes mtu))
                     ;; Congestion Avoidance
                     (let [partial (+ (get state :partial-bytes-acked 0) acked-bytes)]
                       (if (>= partial cwnd)
                         (+ cwnd mtu)
                         cwnd)))
                   cwnd)
        new-partial (if (> acked-bytes 0)
                      (if (<= cwnd ssthresh)
                        0
                        (let [partial (+ (get state :partial-bytes-acked 0) acked-bytes)]
                          (if (>= partial cwnd)
                            (- partial cwnd)
                            partial)))
                      (get state :partial-bytes-acked 0))

        all-empty? (every? #(empty? (:send-queue (val %))) new-streams)
        total-unacked (reduce + (map #(count (:send-queue (val %))) new-streams))

        s1 (-> state
               (assoc :streams new-streams)
               (assoc :flight-size flight-size)
               (assoc :cwnd new-cwnd)
               (assoc :partial-bytes-acked new-partial)
               (assoc :heartbeat-error-count 0)
               (assoc-in [:metrics :unacked-data] total-unacked))
        s2 (if all-empty?
             (update s1 :timers dissoc :t3-rtx)
             s1)]
    {:next-state s2 :next-events []}))"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find process-chunk :sack to patch.")
        content))))

(defn apply-patch []
  (let [c5 (patch-process-chunk-sack core-content)]
    (spit core-path c5)))

(apply-patch)
