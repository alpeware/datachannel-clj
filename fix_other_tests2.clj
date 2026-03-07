(require '[clojure.string :as str])

(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (-> code
                     (str/replace #"(?s)\(is \(= 1 \(count \(:network-out result\)\)\)\)\n                \(is \(= :send-packet \(\:type \(first \(:network-out result\)\)\)\)\)" "(= 1 (count (:network-out result)))")
                     (str/replace #"(?s)\(is \(= 1 \(count \(:network-out result\)\)\)\)\n                \(let \[effect \(first \(:network-out result\)\)\]\n                  \(is \(= :on-error \(\:type effect\)\)\)\n                  \(is \(= :max-retransmissions \(\:cause effect\)\)\)" "(is (= 1 (count (:app-events result))))\n                (let [effect (first (:app-events result))]\n                  (is (= :on-error (:type effect)))\n                  (is (= :max-retransmissions (:cause effect)))")
                     (str/replace #"(?s)\(is \(= 2 \(count \(:network-out result\)\)\)\)\n                \(let \[abort-effect \(first \(:network-out result\)\)\n                      error-effect \(second \(:network-out result\)\)\]\n                  \(is \(= :on-error \(\:type error-effect\)\)\)\n                  \(is \(= :max-retransmissions \(\:cause error-effect\)\)\)" "(is (= 1 (count (:network-out result))))\n                (is (= 1 (count (:app-events result))))\n                (let [abort-effect (first (:network-out result))\n                      error-effect (first (:app-events result))]\n                  (is (= :on-error (:type error-effect)))\n                  (is (= :max-retransmissions (:cause error-effect)))")
                     (str/replace #"let \[\{:keys \[new-state effects\]\} \(core/handle-timeout" "let [{:keys [new-state network-out app-events]} (core/handle-timeout")
                     (str/replace #"\(:packet effect\)" "effect")
                     (str/replace #"\(:packet abort-effect\)" "abort-effect")
                     (str/replace #"\(:packet rtx-effect\)" "rtx-effect")
                     (str/replace #"\(:packet hb-effect\)" "hb-effect")
                     )]
      (spit path new-code)))

(doseq [f ["test/datachannel/sctp_init_ack_robustness_test.clj"
           "test/datachannel/sctp_init_abort_test.clj"
           "test/datachannel/sctp_heartbeat_test.clj"
           "test/datachannel/sctp_recovers_after_successful_ack_test.clj"
           "test/datachannel/sctp_shutdown_timer_test.clj"
           "test/datachannel/sctp_retransmission_test.clj"
           "test/datachannel/sctp_max_message_size_test.clj"]]
  (rewrite-file f)
  (println "Rewrote:" f))
