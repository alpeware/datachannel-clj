(require '[clojure.string :as str])

(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (-> code
                     (str/replace #"(?s)          handle-sctp-packet \#'core/handle-sctp-packet"
                 "          handle-sctp-packet (fn [c p]\n                               (when (and p c)\n                                 (let [state-map @(:state c)\n                                       res (@#'core/handle-sctp-packet state-map p (System/currentTimeMillis))\n                                       next-state (:new-state res)\n                                       network-out (:network-out res)\n                                       app-events (:app-events res)]\n                                   (reset! (:state c) next-state)\n                                   (doseq [out network-out] (.offer (:sctp-out c) out))\n                                   (doseq [evt app-events]\n                                     (case (:type evt)\n                                       :on-message (when-let [cb (:on-message c)] (when (and cb @cb) (@cb (:payload evt))))\n                                       :on-data (when-let [cb (:on-data c)] (when (and cb @cb) (@cb (assoc evt :payload (:payload evt) :stream-id (:stream-id evt)))))\n                                       :on-open (when-let [cb (:on-open c)] (when (and cb @cb) (@cb)))\n                                       :on-error (when-let [cb (:on-error c)] (when (and cb @cb) (@cb (:causes evt))))\n                                       :on-close (when-let [cb (:on-close c)] (when (and cb @cb) (@cb)))\n                                       nil)))))")
                     (str/replace #"\(handle-sctp-packet init-packet server-conn\)" "(handle-sctp-packet server-conn init-packet)")
                     (str/replace #"\(handle-sctp-packet init-ack-packet client-conn\)" "(handle-sctp-packet client-conn init-ack-packet)")
                     (str/replace #"\(handle-sctp-packet cookie-echo-packet server-conn\)" "(handle-sctp-packet server-conn cookie-echo-packet)")
                     (str/replace #"\(handle-sctp-packet cookie-ack-packet client-conn\)" "(handle-sctp-packet client-conn cookie-ack-packet)")
                     (str/replace #"\(handle-sctp-packet duplicate-init-ack-packet client-conn\)" "(handle-sctp-packet client-conn duplicate-init-ack-packet)")
                     (str/replace #"\(handle-sctp-packet shutdown-packet connection\)" "(handle-sctp-packet connection shutdown-packet)")
                     (str/replace #"\(handle-sctp-packet shutdown-ack-packet connection\)" "(handle-sctp-packet connection shutdown-ack-packet)")
                     (str/replace #"\(handle-sctp-packet data-packet server-conn\)" "(handle-sctp-packet server-conn data-packet)")
                     (str/replace #"\(handle-sctp-packet retransmitted-data-packet server-conn\)" "(handle-sctp-packet server-conn retransmitted-data-packet)")
                     (str/replace #"\(handle-sctp-packet sack-packet client-conn\)" "(handle-sctp-packet client-conn sack-packet)")
                     (str/replace #"\(handle-sctp-packet heartbeat-packet connection\)" "(handle-sctp-packet connection heartbeat-packet)")
                     (str/replace #"\(handle-sctp-packet heartbeat-ack-packet connection\)" "(handle-sctp-packet connection heartbeat-ack-packet)")
                     (str/replace #":effects" ":network-out")
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

(doseq [f ["test/datachannel/sctp_heartbeat_test.clj"
           "test/datachannel/sctp_recovers_after_successful_ack_test.clj"
           "test/datachannel/sctp_shutdown_timer_test.clj"
           "test/datachannel/sctp_retransmission_test.clj"]]
  (rewrite-file f)
  (println "Rewrote:" f))
