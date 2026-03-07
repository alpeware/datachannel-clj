(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/sctp_timeout_resends_packet_test.clj")
      new-code (str/replace code
                 #"\{:keys \[new-state effects\]\} \(core/handle-timeout \@state-z :t3-rtx now\)"
                 "{:keys [new-state network-out app-events]} (core/handle-timeout @state-z :t3-rtx now)")]
  (spit "test/datachannel/sctp_timeout_resends_packet_test.clj" new-code))
