(require '[clojure.string :as str])

(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (-> code
                     (str/replace #"(?s)          handle-sctp-packet \(fn \[c p\]"
                 "          handle-sctp-packet (fn [p c]"))]
    (let [new-code (str/replace new-code #"\(handle-sctp-packet (.*?) (.*?)\)" "(handle-sctp-packet $1 $2)")]
      (spit path new-code))))

(doseq [f ["test/datachannel/sctp_heartbeat_test.clj"
           "test/datachannel/sctp_recovers_after_successful_ack_test.clj"
           "test/datachannel/sctp_shutdown_timer_test.clj"
           "test/datachannel/sctp_retransmission_test.clj"
           "test/datachannel/sctp_init_abort_test.clj"
           "test/datachannel/sctp_init_ack_robustness_test.clj"]]
  (rewrite-file f)
  (println "Rewrote:" f))
