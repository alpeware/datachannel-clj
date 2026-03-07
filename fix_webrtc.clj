(require '[clojure.string :as str])
(defn fix [path]
  (let [code (slurp path)
        code (str/replace code #"\(reset! \(:on-message server\)" "(reset! (:on-message server)")
        code (str/replace code #"\(reset! \(:on-data server\)" "(reset! (:on-data server)")]
    (spit path code)))
(fix "test/datachannel/webrtc_extended_test.clj")
(fix "test/datachannel/webrtc_integration_test.clj")
