(ns datachannel.fuzz-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datachannel.core :as dc]))

(def gen-bytes
  "Generates random byte arrays of varying lengths to simulate malformed network packets."
  (gen/fmap byte-array (gen/vector gen/byte 0 2000)))

(def gen-state
  "Generates a raw connection state, randomly choosing between client or server mode."
  (gen/fmap (fn [client-mode?]
              (dc/create-connection {} client-mode?))
            gen/boolean))

(def prop-no-crashes
  "A generative property testing the invariant that the pure `handle-receive` function never throws an unhandled exception or crashes when processing completely randomized, hostile byte arrays."
  (prop/for-all [state gen-state
                 garbage-bytes gen-bytes]
                (let [now-ms (System/currentTimeMillis)
                      res (dc/handle-receive state garbage-bytes now-ms nil)]
                  (and (map? res)
                       (contains? res :new-state)))))

(defspec fuzz-handle-receive-no-crashes (Integer/parseInt (or (System/getenv "FUZZ_ITERS") "100")) prop-no-crashes)
