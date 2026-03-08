(ns patch-step4b
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-packetize3 [content]
  (let [search-str "                    (recur remaining-ctrl
                           new-streams
                           (conj bundled-chunks data-chunk)
                           (+ current-size chunk-size)))"
        replace-str "                      (let [state-with-flight (assoc state :flight-size new-flight-size)]
                        (recur remaining-ctrl
                               new-streams
                               (conj bundled-chunks data-chunk)
                               (+ current-size chunk-size))))"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find packetize part 3 to patch.")
        content))))

(defn apply-patch []
  (let [c3 (patch-packetize3 core-content)]
    (spit core-path c3)))

(apply-patch)
