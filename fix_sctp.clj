(require '[clojure.string :as str])
(let [code (slurp "src/datachannel/sctp.clj")
      new-code (str/replace code
                 #"\(\.putInt buf \(unchecked-int \(or \(\:verification-tag packet\) 0\)\)\)"
                 "(.putInt buf (unchecked-int (:verification-tag packet)))")]
  (spit "src/datachannel/sctp.clj" new-code))
