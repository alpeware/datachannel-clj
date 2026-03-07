(require '[clojure.string :as str])
(let [code (slurp "test/datachannel/webrtc_extended_test.clj")
      new-code (-> code
                   (str/replace #":on-open \(atom \(fn \[\]\n                                     \(reset! client-opened true\)\)\)" ":on-open (atom (fn [] (reset! client-opened true)))")
                   (str/replace #":on-message \(atom \(fn \[msg\]\n                                        \(swap! client-messages conj msg\)\)\)" ":on-message (atom (fn [msg] (swap! client-messages conj msg)))")
                   (str/replace #":on-data \(atom nil\)" ":on-data (atom nil)")
                   (str/replace #":on-error \(atom nil\)" ":on-error (atom nil)")
                   )]
  (spit "test/datachannel/webrtc_extended_test.clj" new-code))
