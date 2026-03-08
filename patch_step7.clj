(ns patch-step7
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-reassemble-stream [content]
  (let [search-str "    (loop [remaining sorted-q
           current-msg []
           new-q []
           app-events []
           next-ssn (get stream-data :next-ssn 0)]
      (if (empty? remaining)
        {:new-stream (assoc stream-data :recv-queue new-q :next-ssn next-ssn) :app-events app-events}"
        replace-str "    (loop [remaining sorted-q
           current-msg []
           new-q []
           app-events []
           next-ssn (get stream-data :next-ssn 0)]
      (if (empty? remaining)
        (let [final-q (if (seq current-msg) (into new-q current-msg) new-q)]
          {:new-stream (assoc stream-data :recv-queue final-q :next-ssn next-ssn) :app-events app-events})"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find reassemble-stream to patch.")
        content))))

(defn patch-reassemble-destructure [content]
  (let [search-str "              stream-data (get-in current-state [:streams stream-id])
              {:keys [new-stream app-events:stream]} (reassemble-stream stream-data)
              events-with-dcep (reduce (fn [acc event]
                                         (if (= (:protocol event) :webrtc/dcep)
                                           ;; DCEP handling should ideally happen earlier or generate next-state
                                           ;; but to keep it simple we emit :dcep-message and handle it in wrapper
                                           ;; Let's inline DCEP ack response here to state
                                           acc
                                           (conj acc event)))
                                       []
                                       app-events:stream)
              dcep-events (filter #(= (:protocol %) :webrtc/dcep) app-events:stream)"
        replace-str "              stream-data (get-in current-state [:streams stream-id])
              {:keys [new-stream app-events] :as res} (reassemble-stream stream-data)
              app-events-stream app-events
              events-with-dcep (reduce (fn [acc event]
                                         (if (= (:protocol event) :webrtc/dcep)
                                           ;; DCEP handling should ideally happen earlier or generate next-state
                                           ;; but to keep it simple we emit :dcep-message and handle it in wrapper
                                           ;; Let's inline DCEP ack response here to state
                                           acc
                                           (conj acc event)))
                                       []
                                       app-events-stream)
              dcep-events (filter #(= (:protocol %) :webrtc/dcep) app-events-stream)"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find reassemble destructure to patch.")
        content))))

(defn apply-patch []
  (let [c7 (patch-reassemble-stream core-content)
        c8 (patch-reassemble-destructure c7)]
    (spit core-path c8)))

(apply-patch)
