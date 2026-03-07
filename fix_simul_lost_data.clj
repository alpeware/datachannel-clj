(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/sctp_establish_simultaneous_lost_data_test.clj")
      new-code (str/replace code
                 #"nil\)\)\)\)\)\]"
                 "nil)))))")]
  (spit "test/datachannel/sctp_establish_simultaneous_lost_data_test.clj" new-code))
