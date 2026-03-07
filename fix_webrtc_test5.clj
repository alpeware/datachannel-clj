(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/webrtc_extended_test.clj")
      new-code (-> code
                   (str/replace #"\n    \(swap! \(\:state server\) assoc :on-data \(fn \[\{\:keys \[payload protocol\]\}\]\n              \(when \(\= protocol :webrtc/binary\)\n                \(deliver binary-received payload\)\)\)\)" "\n    (swap! (:state server) assoc :on-data (fn [{:keys [payload protocol]}]\n              (when (= protocol :webrtc/binary)\n                (deliver binary-received payload))))")
                   (str/replace #"\n    \(swap! \(\:state server\) assoc :on-message \(fn \[msg\]\n              \(swap! received-messages conj msg\)\n              \(when \(\= \(count \@received-messages\) num-messages\)\n                \(deliver all-messages-received true\)\)\)\)" "\n    (swap! (:state server) assoc :on-message (fn [msg]\n              (swap! received-messages conj msg)\n              (when (= (count @received-messages) num-messages)\n                (deliver all-messages-received true))))")
                   (str/replace #"\n    \(swap! \(\:state server\) assoc :on-message \(fn \[msg\]\n              \(deliver message-received msg\)\)\)" "\n    (swap! (:state server) assoc :on-message (fn [msg]\n              (deliver message-received msg)))")
                   )]
  (spit "test/datachannel/webrtc_extended_test.clj" new-code))
