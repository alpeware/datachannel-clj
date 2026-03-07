(require '[clojure.string :as str])
(let [code (slurp "src/datachannel/core.clj")
      new-code (str/replace code
                 #":zero-checksum\? \(\:zero-checksum\? options\)"
                 ":zero-checksum? (:zero-checksum? options)\n                    :on-message (atom nil)\n                    :on-data (atom nil)\n                    :on-open (atom nil)\n                    :on-error (atom nil)")]
  (spit "src/datachannel/core.clj" new-code))
