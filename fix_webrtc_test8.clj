(require '[clojure.string :as str])
(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (-> code
                     (str/replace #"\(reset! \(:on-message server\)" "(reset! (:on-message server)")
                     (str/replace #"\(reset! \(:on-data server\)" "(reset! (:on-data server)")
                     (str/replace #"\(reset! \(:on-open server\)" "(reset! (:on-open server)")
                     (str/replace #"\(reset! \(:on-error server\)" "(reset! (:on-error server)")
                     (str/replace #"\n    \(reset! \(:on-message server\)\n            \(fn \[msg\]\n              \(println \"Clojure received message:\" msg\)\n              \(dc/send-msg server \"Pong from clj\"\)\n              \(deliver message-received true\)\)\)" "\n    (swap! (:state server) assoc :on-message (atom (fn [msg]\n              (println \"Clojure received message:\" msg)\n              (dc/send-msg server \"Pong from clj\")\n              (deliver message-received true))))")
                     (str/replace #"\n    \(reset! \(:on-data server\)\n            \(fn \[\{:keys \[payload protocol\]\}\]\n              \(when \(= protocol :webrtc/binary\)\n                \(deliver binary-received payload\)\)\)\)" "\n    (swap! (:state server) assoc :on-data (atom (fn [{:keys [payload protocol]}]\n              (when (= protocol :webrtc/binary)\n                (deliver binary-received payload)))))")
                     (str/replace #"\n    \(reset! \(:on-message server\)\n            \(fn \[msg\]\n              \(swap! received-messages conj msg\)\n              \(when \(= \(count \@received-messages\) num-messages\)\n                \(deliver all-messages-received true\)\)\)\)" "\n    (swap! (:state server) assoc :on-message (atom (fn [msg]\n              (swap! received-messages conj msg)\n              (when (= (count @received-messages) num-messages)\n                (deliver all-messages-received true)))))")
                     (str/replace #"\n    \(reset! \(:on-message server\)\n            \(fn \[msg\]\n              \(deliver message-received msg\)\)\)" "\n    (swap! (:state server) assoc :on-message (atom (fn [msg]\n              (deliver message-received msg))))")
                     (str/replace #":on-open \(atom \(fn \[\]\n                                     \(reset! client-opened true\)\)\)" ":on-open (atom (fn [] (reset! client-opened true)))")
                     )]
    (spit path new-code)))
(rewrite-file "test/datachannel/webrtc_extended_test.clj")
(rewrite-file "test/datachannel/webrtc_integration_test.clj")
