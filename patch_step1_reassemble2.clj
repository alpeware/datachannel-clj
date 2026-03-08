(ns patch-step1-reassemble2
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-handle-sctp-packet [content]
  (let [search-str "                           (into app-events next-events)))))]
      (let [reassembled (reassemble (:new-state res) (:app-events res))]
        (packetize (:new-state reassembled) (:app-events reassembled)))))))"
        replace-str "                           (into app-events next-events)))))]
      (let [reassembled (reassemble (:new-state res) (:app-events res))]
        (packetize (:new-state reassembled) (:app-events reassembled))))))"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find handle-sctp-packet to patch.")
        content))))

(defn apply-patch []
  (let [content2 (patch-handle-sctp-packet core-content)]
    (spit core-path content2)))

(apply-patch)
