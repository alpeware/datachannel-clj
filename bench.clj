(ns bench
  (:require [datachannel.sctp :as sctp])
  (:import [java.nio ByteBuffer]))

(def params {:heartbeat nil :cookie nil :hostname nil})

(defn run-bench []
  (let [buf (ByteBuffer/allocate 1024)]
    ;; encode with nil values (which translates to empty byte arrays)
    (sctp/encode-params buf params)
    (.flip buf)
    (let [encoded-len (.remaining buf)]
      (println "Benchmarking decode-params with empty values (baseline)...")
      ;; warmup
      (dotimes [_ 1000]
        (.position buf 0)
        (sctp/decode-params buf encoded-len))

      (time
       (dotimes [_ 1000000]
         (.position buf 0)
         (sctp/decode-params buf encoded-len))))))

(run-bench)
