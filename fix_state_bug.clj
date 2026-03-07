(require '[clojure.string :as str])
(let [code (slurp "src/datachannel/core.clj")
      new-code (-> code
                   (str/replace #":keys \[next-state network-out app-events\]\} \(handle-sctp-packet" ":keys [new-state network-out app-events]} (handle-sctp-packet")
                   (str/replace #"\(reset! \(\:state connection\) next-state\)" "(reset! (:state connection) new-state)")
                   (str/replace #"\(let \[t \(or \(:next-tsn @state\) 0\)\]" "(let [t (:next-tsn @state)]")
                   (str/replace #"\(let \[s \(or \(:ssn @state\) 0\)\]" "(let [s (:ssn @state)]")
                   (str/replace #"\(or \(\:remote-ver-tag \@state\) 0\)" "(:remote-ver-tag @state)")
                   (str/replace #"\(or \(\:next-tsn \@state\) 0\)" "(:next-tsn @state)")
                   (str/replace #"\(or \(\:ssn \@state\) 0\)" "(:ssn @state)")
                   )]
  (spit "src/datachannel/core.clj" new-code))
