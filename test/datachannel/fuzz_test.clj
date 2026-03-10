(ns datachannel.fuzz-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datachannel.core :as dc]))

(def gen-bytes
  (gen/fmap byte-array (gen/vector gen/byte 0 2000)))

(def gen-state
  (gen/fmap (fn [client-mode?]
              (dc/create-connection {} client-mode?))
            gen/boolean))

(def prop-no-crashes
  (prop/for-all [state gen-state
                 garbage-bytes gen-bytes]
                (let [now-ms (System/currentTimeMillis)
                      res (dc/handle-receive state garbage-bytes now-ms nil)]
                  (and (map? res)
                       (contains? res :new-state)))))

(defspec fuzz-handle-receive-no-crashes 1000 prop-no-crashes)
