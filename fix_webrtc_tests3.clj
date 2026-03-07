(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/webrtc_extended_test.clj")
      new-code (-> code
                   (str/replace #"\n    \(reset! \(:on-data server\)(.*?)\n\s*\(fn \[\{\:keys \[payload protocol\]\}\](.*?)\n" "\n    (swap! (:state server) assoc :on-data (atom (fn [{:keys [payload protocol]}] (when (= protocol :webrtc/binary) (deliver binary-received payload)))))")
                   (str/replace #"\n    \(reset! \(:on-message server\)\n            \(fn \[msg\]\n              \(swap! received-messages conj msg\)\n              \(when \(= \(count \@received-messages\) num-messages\)\n                \(deliver all-messages-received true\)\)\)\)" "\n    (swap! (:state server) assoc :on-message (atom (fn [msg] (swap! received-messages conj msg) (when (= (count @received-messages) num-messages) (deliver all-messages-received true)))))")
                   (str/replace #"\n    \(reset! \(:on-message server\)\n            \(fn \[msg\]\n              \(deliver message-received msg\)\)\)" "\n    (swap! (:state server) assoc :on-message (atom (fn [msg] (deliver message-received msg))))")
                   )]
  (spit "test/datachannel/webrtc_extended_test.clj" new-code))
