(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/webrtc_integration_test.clj")
      new-code (-> code
                   (str/replace #"\n    \(reset! \(:on-message server\)\n            \(fn \[msg\]\n              \(println \"Clojure received message:\" msg\)\n              \(dc/send-msg server \"Pong from clj\"\)\n              \(deliver message-received true\)\)\)" "\n    (swap! (:state server) assoc :on-message (atom (fn [msg] (println \"Clojure received message:\" msg) (dc/send-msg server \"Pong from clj\") (deliver message-received true))))")
                   )]
  (spit "test/datachannel/webrtc_integration_test.clj" new-code))
