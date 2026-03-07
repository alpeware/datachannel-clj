(require '[clojure.string :as str])

(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (-> code
                     (str/replace #"effects" "network-out")
                     (str/replace #"\(first \(filter \#\(\= \(:type %\) :send-packet\) network-out\)\)" "(first network-out)")
                     (str/replace #"\{:keys \[new-state network-out app-events\]\} \(core/handle-timeout \@state-atom :t3-rtx current-time\)" "{:keys [new-state network-out app-events]} (core/handle-timeout @state-atom :t3-rtx current-time)")
                     )]
      (spit path new-code)))

(doseq [f ["test/datachannel/sctp_heartbeat_test.clj"
           "test/datachannel/sctp_recovers_after_successful_ack_test.clj"
           "test/datachannel/sctp_shutdown_timer_test.clj"
           "test/datachannel/sctp_retransmission_test.clj"]]
  (rewrite-file f)
  (println "Rewrote:" f))
