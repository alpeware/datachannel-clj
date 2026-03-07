(require '[clojure.string :as str])

(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (str/replace code #"(?s)          handle-sctp-packet \#'core/handle-sctp-packet"
                 "          handle-sctp-packet (fn [c p]\n                               (when (and p c)\n                                 (let [state-map @(:state c)\n                                       res (@#'core/handle-sctp-packet state-map p (System/currentTimeMillis))\n                                       next-state (:new-state res)\n                                       network-out (:network-out res)\n                                       app-events (:app-events res)]\n                                   (reset! (:state c) next-state)\n                                   (doseq [out network-out] (.offer (:sctp-out c) out))\n                                   (doseq [evt app-events]\n                                     (case (:type evt)\n                                       :on-message (when-let [cb (:on-message c)] (when (and cb @cb) (@cb (:payload evt))))\n                                       :on-data (when-let [cb (:on-data c)] (when (and cb @cb) (@cb (assoc evt :payload (:payload evt) :stream-id (:stream-id evt)))))\n                                       :on-open (when-let [cb (:on-open c)] (when (and cb @cb) (@cb)))\n                                       :on-error (when-let [cb (:on-error c)] (when (and cb @cb) (@cb (:causes evt))))\n                                       :on-close (when-let [cb (:on-close c)] (when (and cb @cb) (@cb)))\n                                       nil)))))")]
      (spit path new-code)))

(doseq [f ["test/datachannel/sctp_heartbeat_test.clj"
           "test/datachannel/sctp_recovers_after_successful_ack_test.clj"]]
  (rewrite-file f)
  (println "Rewrote:" f))
