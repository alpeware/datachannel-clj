(ns patch-step4
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-packetize [content]
  (let [search-str "              (let [[stream-id stream-data] active-stream-entry
                    q (:send-queue stream-data)
                    data-idx (first (keep-indexed #(when-not (:sent? %2) %1) q))
                    data-item (nth q data-idx)
                    data-chunk (:chunk data-item)
                    ;; Approximate size, +16 for chunk header overhead
                    chunk-size (+ 16 (if (:payload data-chunk) (alength ^bytes (:payload data-chunk)) 0))]"
        replace-str "              (let [[stream-id stream-data] active-stream-entry
                    q (:send-queue stream-data)
                    data-idx (first (keep-indexed #(when-not (:sent? %2) %1) q))
                    data-item (nth q data-idx)
                    data-chunk (:chunk data-item)
                    ;; Approximate size, +16 for chunk header overhead
                    chunk-size (+ 16 (if (:payload data-chunk) (alength ^bytes (:payload data-chunk)) 0))
                    cwnd (get state :cwnd 1000000)
                    flight-size (get state :flight-size 0)]
                (if (> (+ flight-size chunk-size) cwnd)
                  ;; Congestion window full, halt pulling new data
                  {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams)
                   :network-out (if (seq bundled-chunks)
                                  [{:src-port (get state :local-port 5000)
                                    :dst-port (get state :remote-port 5000)
                                    :verification-tag (get state :remote-ver-tag 0)
                                    :chunks bundled-chunks}]
                                  [])
                   :app-events app-events}"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find packetize part 1 to patch.")
        content))))

(defn patch-packetize2 [content]
  (let [search-str "                (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
                  (let [new-q (assoc q data-idx (assoc data-item :sent? true))
                        new-streams (assoc-in current-streams [stream-id :send-queue] new-q)]
                    (recur remaining-ctrl
                           new-streams
                           (conj bundled-chunks data-chunk)
                           (+ current-size chunk-size)))"
        replace-str "                  (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
                    (let [new-q (assoc q data-idx (assoc data-item :sent? true))
                          new-streams (assoc-in current-streams [stream-id :send-queue] new-q)
                          ;; Only increase flight-size for *new* transmissions (retries = 0)
                          ;; Retransmissions don't increase flight-size
                          new-flight-size (if (= (:retries data-item) 0)
                                            (+ flight-size chunk-size)
                                            flight-size)
                          state-with-flight (assoc state :flight-size new-flight-size)]
                      (recur remaining-ctrl
                             new-streams
                             (conj bundled-chunks data-chunk)
                             (+ current-size chunk-size)))"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find packetize part 2 to patch.")
        content))))


(defn apply-patch []
  (let [c1 (patch-packetize core-content)
        c2 (patch-packetize2 c1)]
    (spit core-path c2)))

(apply-patch)
