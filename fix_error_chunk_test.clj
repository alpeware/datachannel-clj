(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/sctp_error_chunk_test.clj")
      new-code (-> code
                   (str/replace #":on-error on-error" "")
                   (str/replace #"handle-sctp-packet \#'core/handle-sctp-packet" "")
                   (str/replace #"\(handle-sctp-packet error-packet conn\)"
                                "(let [{:keys [app-events]} (@#'core/handle-sctp-packet @state-atom error-packet (System/currentTimeMillis))\n            evt (first app-events)]\n        (when (= (:type evt) :on-error)\n          (reset! error-called true)\n          (reset! received-causes (:causes evt))))")
                   )]
  (spit "test/datachannel/sctp_error_chunk_test.clj" new-code))
