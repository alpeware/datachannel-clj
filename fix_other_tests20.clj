(require '[clojure.string :as str])

(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (str/replace code #"\(\@\#'core/handle-sctp-packet \@state-atom (.*?) \(System/currentTimeMillis\)\)"
                 "(@#'core/handle-sctp-packet @state-atom $1 (System/currentTimeMillis))")]
      (let [new-code (str/replace new-code #"\(let \[res \(\#'core/handle-sctp-packet \@state-atom (.*?) \(System/currentTimeMillis\)\)\]\n  \(reset! state-atom \(:new-state res\)\)\)" "(let [res (@#'core/handle-sctp-packet @state-atom $1 (System/currentTimeMillis))]\n  (reset! state-atom (:new-state res)))")]
        (spit path new-code))))

(doseq [f ["test/datachannel/sctp_heartbeat_test.clj"
           "test/datachannel/sctp_recovers_after_successful_ack_test.clj"]]
  (rewrite-file f)
  (println "Rewrote:" f))
