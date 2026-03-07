(require '[clojure.string :as str])

(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (str/replace code #"\(\@\#'core/handle-sctp-packet state-map p \(System/currentTimeMillis\)\)"
                 "(#'core/handle-sctp-packet state-map p (System/currentTimeMillis))")]
      (spit path new-code)))

(doseq [f ["test/datachannel/sctp_heartbeat_test.clj"
           "test/datachannel/sctp_recovers_after_successful_ack_test.clj"]]
  (rewrite-file f)
  (println "Rewrote:" f))
