(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/integration_test.clj")
      new-code (-> code
                   (str/replace #"\(reset! \(:on-message server\)" "(reset! (:on-message (:connection server))")
                   (str/replace #"\(reset! \(:on-message client\)" "(reset! (:on-message (:connection client))")
                   (str/replace #"\(reset! \(:on-open client\)" "(reset! (:on-open (:connection client))")
                   (str/replace #"\n              \(dc/send-msg client \"Hello\"\)\)\)\)" "\n              (dc/send-msg (:connection client) \"Hello\"))))")
                   (str/replace #"\n                \(dc/send-msg server \"World\"\)\)\)\)" "\n                (dc/send-msg (:connection server) \"World\"))))")
                   )]
  (spit "test/datachannel/integration_test.clj" new-code))
