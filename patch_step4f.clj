(ns patch-step4f
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-packetize6 [content]
  (let [search-str "            (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
              (recur (rest remaining-ctrl)
                     current-streams
                     (conj bundled-chunks chunk)
                     (+ current-size chunk-size))
              ;; Stop if it exceeds MTU (unlikely for control chunks but adhering to strict boundary)
              {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams)
               :network-out [{:src-port (get state :local-port 5000)"
        replace-str "            (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
              (recur (rest remaining-ctrl)
                     current-streams
                     (conj bundled-chunks chunk)
                     (+ current-size chunk-size)
                     current-flight-size)
              ;; Stop if it exceeds MTU (unlikely for control chunks but adhering to strict boundary)
              {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
               :network-out [{:src-port (get state :local-port 5000)"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find packetize part 6 to patch.")
        content))))

(defn patch-packetize7 [content]
  (let [search-str "          (let [active-stream-entry (first (filter #(some (fn [item] (not (:sent? item))) (:send-queue (val %))) current-streams))]
            (if active-stream-entry
              (let [[stream-id stream-data] active-stream-entry
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
                   :app-events app-events}

                  (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
                    (let [new-q (assoc q data-idx (assoc data-item :sent? true))
                          new-streams (assoc-in current-streams [stream-id :send-queue] new-q)
                          ;; Only increase flight-size for *new* transmissions (retries = 0)
                          ;; Retransmissions don't increase flight-size
                          new-flight-size (if (= (:retries data-item) 0)
                                            (+ flight-size chunk-size)
                                            flight-size)
                          ;; Note: To persist this between recurs we'd need to thread state or update `current-streams` equivalent,
                          ;; for simplicity in this packetize loop we'll rely on pulling less or tracking flight-size loosely
                          ]
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
               :app-events app-events})))))))"
        replace-str "          (let [active-stream-entry (first (filter #(some (fn [item] (not (:sent? item))) (:send-queue (val %))) current-streams))]
            (if active-stream-entry
              (let [[stream-id stream-data] active-stream-entry
                    q (:send-queue stream-data)
                    data-idx (first (keep-indexed #(when-not (:sent? %2) %1) q))
                    data-item (nth q data-idx)
                    data-chunk (:chunk data-item)
                    ;; Approximate size, +16 for chunk header overhead
                    chunk-size (+ 16 (if (:payload data-chunk) (alength ^bytes (:payload data-chunk)) 0))
                    cwnd (get state :cwnd 1000000)]
                (if (> (+ current-flight-size chunk-size) cwnd)
                  ;; Congestion window full, halt pulling new data
                  {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
                   :network-out (if (seq bundled-chunks)
                                  [{:src-port (get state :local-port 5000)
                                    :dst-port (get state :remote-port 5000)
                                    :verification-tag (get state :remote-ver-tag 0)
                                    :chunks bundled-chunks}]
                                  [])
                   :app-events app-events}

                  (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
                    (let [new-q (assoc q data-idx (assoc data-item :sent? true))
                          new-streams (assoc-in current-streams [stream-id :send-queue] new-q)
                          ;; Only increase flight-size for *new* transmissions (retries = 0)
                          ;; Retransmissions don't increase flight-size
                          new-flight-size (if (= (:retries data-item) 0)
                                            (+ current-flight-size chunk-size)
                                            current-flight-size)]
                      (recur remaining-ctrl
                             new-streams
                             (conj bundled-chunks data-chunk)
                             (+ current-size chunk-size)
                             new-flight-size))

                  ;; Halting when MTU is reached
                  {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams :flight-size current-flight-size)
                   :network-out [{:src-port (get state :local-port 5000)
                                  :dst-port (get state :remote-port 5000)
                                  :verification-tag (get state :remote-ver-tag 0)
                                  :chunks bundled-chunks}]
                   :app-events app-events})))

              ;; No more data in streams
              {:new-state (assoc state :pending-control-chunks [] :streams current-streams :flight-size current-flight-size)
               :network-out (if (seq bundled-chunks)
                              [{:src-port (get state :local-port 5000)
                                :dst-port (get state :remote-port 5000)
                                :verification-tag (get state :remote-ver-tag 0)
                                :chunks bundled-chunks}]
                              [])
               :app-events app-events})))))))"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find packetize part 7 to patch.")
        content))))

(defn apply-patch []
  (let [c6 (patch-packetize6 core-content)
        c7 (patch-packetize7 c6)]
    (spit core-path c7)))

(apply-patch)
