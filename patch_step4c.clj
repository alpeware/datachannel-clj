(ns patch-step4c
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-packetize3 [content]
  (let [search-str "                  (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
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
                             (+ current-size chunk-size)))

                  ;; Halting when MTU is reached
                  {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams)"
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
                             (+ current-size chunk-size)))

                  ;; Halting when MTU is reached
                  {:new-state (assoc state :pending-control-chunks remaining-ctrl :streams current-streams)"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find packetize part 3 to patch.")
        content))))

(defn apply-patch []
  (let [c3 (patch-packetize3 core-content)]
    (spit core-path c3)))

(apply-patch)
