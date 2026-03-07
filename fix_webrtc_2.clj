(require '[clojure.string :as str])
(defn fix [path]
  (let [code (slurp path)
        code (str/replace code #"\n    \(reset! \(:on-message server\)\n            \(fn \[msg\]\n              \(println \"Clojure received message:\" msg\)\n              \(dc/send-msg server \"Pong from clj\"\)\n              \(deliver message-received true\)\)\)" "\n    (swap! (:state server) assoc :on-message (atom (fn [msg]\n              (println \"Clojure received message:\" msg)\n              (dc/send-msg server \"Pong from clj\")\n              (deliver message-received true))))")
        code (str/replace code #"\n    \(reset! \(:on-data server\)\n            \(fn \[\{:keys \[payload protocol\]\}\]\n              \(when \(= protocol :webrtc/binary\)\n                \(deliver binary-received payload\)\)\)\)" "\n    (swap! (:state server) assoc :on-data (atom (fn [{:keys [payload protocol]}]\n              (when (= protocol :webrtc/binary)\n                (deliver binary-received payload)))))")
        code (str/replace code #"\n    \(reset! \(:on-message server\)\n            \(fn \[msg\]\n              \(swap! received-messages conj msg\)\n              \(when \(= \(count \@received-messages\) num-messages\)\n                \(deliver all-messages-received true\)\)\)\)" "\n    (swap! (:state server) assoc :on-message (atom (fn [msg]\n              (swap! received-messages conj msg)\n              (when (= (count @received-messages) num-messages)\n                (deliver all-messages-received true)))))")
        code (str/replace code #"\n    \(reset! \(:on-message server\)\n            \(fn \[msg\]\n              \(deliver message-received msg\)\)\)" "\n    (swap! (:state server) assoc :on-message (atom (fn [msg]\n              (deliver message-received msg))))")]
    (spit path code)))
(fix "test/datachannel/webrtc_extended_test.clj")
(fix "test/datachannel/webrtc_integration_test.clj")
