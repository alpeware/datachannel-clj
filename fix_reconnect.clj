(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/sctp_reconnect_test.clj")
      new-code (str/replace code
                 #"nil\)\)\)\)\)\]\]"
                 "nil)))))]")]
  (spit "test/datachannel/sctp_reconnect_test.clj" new-code))
