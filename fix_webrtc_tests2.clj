(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/webrtc_extended_test.clj")
      new-code (-> code
                   (str/replace #"\n    \(reset! \(:on-data server\)\n            \(fn \[\{:keys \[payload protocol\]\}\]\n              \(when \(= protocol :webrtc/binary\)\n                \(deliver binary-received payload\)\)\)\)"
                   "\n    (reset! (:on-data server)\n            (fn [{:keys [payload protocol]}]\n              (when (= protocol :webrtc/binary)\n                (deliver binary-received payload))))")
                   )]
  (spit "test/datachannel/webrtc_extended_test.clj" new-code))
