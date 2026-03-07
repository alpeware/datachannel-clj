(require '[clojure.string :as str])
(defn rewrite-file [path]
  (let [code (slurp path)
        new-code (-> code
                     (str/replace #"\(:on-message server\)" "(:on-message (:state server))")
                     (str/replace #"\(:on-data server\)" "(:on-data (:state server))")
                     (str/replace #"\(:on-open server\)" "(:on-open (:state server))")
                     (str/replace #"\(:on-error server\)" "(:on-error (:state server))")
                     )]
    (spit path new-code)))
(rewrite-file "test/datachannel/webrtc_extended_test.clj")
(rewrite-file "test/datachannel/webrtc_integration_test.clj")
