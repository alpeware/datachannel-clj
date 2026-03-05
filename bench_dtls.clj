(ns bench-dtls
  (:require [clojure.string :as string])
  (:import [java.security MessageDigest SecureRandom]
           [java.util HexFormat]))

(defn fingerprint-old [bytes]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md bytes)
    (->> (.digest md)
         (map #(format "%02X" (bit-and % 0xff)))
         (string/join ":"))))

(defn fingerprint-new [bytes]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md bytes)
    (-> (HexFormat/ofDelimiter ":")
        (.withUpperCase)
        (.formatHex (.digest md)))))

(def dummy-bytes (let [b (byte-array 1000)]
                   (.nextBytes (SecureRandom.) b)
                   b))

(println "Old:" (fingerprint-old dummy-bytes))
(println "New:" (fingerprint-new dummy-bytes))
(println "Equal?" (= (fingerprint-old dummy-bytes) (fingerprint-new dummy-bytes)))

(println "Benchmarking old...")
(time (dotimes [_ 10000] (fingerprint-old dummy-bytes)))

(println "Benchmarking new...")
(time (dotimes [_ 10000] (fingerprint-new dummy-bytes)))
