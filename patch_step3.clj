(ns patch-step3
  (:require [clojure.string :as str]))

(def core-path "src/datachannel/core.clj")
(def core-content (slurp core-path))

(defn patch-create-connection [content]
  (let [search-str "                                  :metrics {:tx-packets 0
                                            :rx-packets 0
                                            :tx-bytes   0
                                            :rx-bytes   0
                                            :retransmissions 0
                                            :unacked-data 0
                                            :uses-zero-checksum (boolean (:zero-checksum? options))}})"
        replace-str "                                  :metrics {:tx-packets 0
                                            :rx-packets 0
                                            :tx-bytes   0
                                            :rx-bytes   0
                                            :retransmissions 0
                                            :unacked-data 0
                                            :uses-zero-checksum (boolean (:zero-checksum? options))}
                                  :cwnd (let [mtu (get options :mtu 1200)]
                                          (min (* 4 mtu) (max (* 2 mtu) 4380)))
                                  :ssthresh 65535 ; Initial peer rwnd or 65535
                                  :flight-size 0
                                  :partial-bytes-acked 0})"]
    (if (str/includes? content search-str)
      (str/replace content search-str replace-str)
      (do
        (println "Warning: Could not find create-connection to patch.")
        content))))

(defn apply-patch []
  (let [content2 (patch-create-connection core-content)]
    (spit core-path content2)))

(apply-patch)
