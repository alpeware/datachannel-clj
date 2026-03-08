(ns patch-step4e
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-packetize5 [content]
  (let [search-str "(defn packetize [state app-events]
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
        (if (seq remaining-ctrl)"
        replace-str "(defn packetize [state app-events]
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
             current-size 0
             current-flight-size (get state :flight-size 0)]
        (if (seq remaining-ctrl)"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find packetize part 5 to patch.")
        content))))

(defn patch-packetize6 [content]
  (let [search-str "              (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
              (recur (rest remaining-ctrl)
                     current-streams
                     (conj bundled-chunks chunk)
                     (+ current-size chunk-size))"
        replace-str "              (if (or (empty? bundled-chunks) (<= (+ current-size chunk-size) max-payload-size))
              (recur (rest remaining-ctrl)
                     current-streams
                     (conj bundled-chunks chunk)
                     (+ current-size chunk-size)
                     current-flight-size)"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find packetize part 6 to patch.")
        content))))

(defn apply-patch []
  (let [c5 (patch-packetize5 core-content)
        c6 (patch-packetize6 c5)]
    (spit core-path c6)))

(apply-patch)
