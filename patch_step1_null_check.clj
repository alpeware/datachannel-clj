(ns patch-step1-null-check
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-reassemble-stream [content]
  (let [search-str "              flags (:flags chunk)
              u-bit? (pos? (bit-and flags 4))
              b-bit? (pos? (bit-and flags 2))
              e-bit? (pos? (bit-and flags 1))]"
        replace-str "              flags (or (:flags chunk) 0)
              u-bit? (pos? (bit-and flags 4))
              b-bit? (pos? (bit-and flags 2))
              e-bit? (pos? (bit-and flags 1))]"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find flags null check patch.")
        content))))

(defn apply-patch []
  (let [content2 (patch-reassemble-stream core-content)]
    (spit core-path content2)))

(apply-patch)
