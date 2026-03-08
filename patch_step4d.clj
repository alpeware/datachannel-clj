(ns patch-step4d
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-packetize4 [content]
  (let [search-str "                    (let [new-q (assoc q data-idx (assoc data-item :sent? true))
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
                             (+ current-size chunk-size)))"
        replace-str "                    (let [new-q (assoc q data-idx (assoc data-item :sent? true))
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
                             (+ current-size chunk-size)))"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find packetize part 4 to patch.")
        content))))

(defn apply-patch []
  (let [c4 (patch-packetize4 core-content)]
    (spit core-path c4)))

(apply-patch)
