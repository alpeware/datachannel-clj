(require '[clojure.string :as str])
(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (-> code
                     (str/replace #"\(:on-message \(\:state server\)\)" "(:on-message server)")
                     (str/replace #"\(:on-data \(\:state server\)\)" "(:on-data server)")
                     (str/replace #"\(:on-open \(\:state server\)\)" "(:on-open server)")
                     (str/replace #"\(:on-error \(\:state server\)\)" "(:on-error server)")
                     (str/replace #"\(swap! \(\:state server\) assoc :on-data \(fn" "(swap! (:state server) assoc :on-data (atom (fn")
                     (str/replace #"payload\)\)\)\)" "payload))))))")
                     (str/replace #"\(swap! \(\:state server\) assoc :on-message \(fn" "(swap! (:state server) assoc :on-message (atom (fn")
                     (str/replace #"true\)\)\)\)" "true))))))")
                     (str/replace #"msg\)\)\)" "msg))))")
                     (str/replace #"true\)\)\)" "true))))")
                     )]
    (spit path new-code)))
(rewrite-file "test/datachannel/webrtc_extended_test.clj")
(rewrite-file "test/datachannel/webrtc_integration_test.clj")
