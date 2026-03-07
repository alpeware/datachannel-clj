(require '[clojure.string :as str])

(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (str/replace code #"\(\#'core/handle-sctp-packet (.*?) \{(.*?)\}\)"
                 "(let [res (#'core/handle-sctp-packet @state-atom {:src-port 5000\n                                        :dst-port 5000\n                                        :verification-tag 5678\n                                        :chunks [{:type :heartbeat-ack\n                                                  :params (:params chunk)}]} (System/currentTimeMillis))]\n  (reset! state-atom (:new-state res)))")]
      (spit path new-code)))

(doseq [f ["test/datachannel/sctp_heartbeat_test.clj"
           "test/datachannel/sctp_recovers_after_successful_ack_test.clj"]]
  (rewrite-file f)
  (println "Rewrote:" f))
