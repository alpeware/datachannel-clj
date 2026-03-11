(ns fix-stun
  (:require [clojure.string :as str]))

(let [content (slurp "src/datachannel/stun.clj")
      target "(let [hmac (compute-hmac-sha1 password data-to-sign)]"
      new-target "(let [hmac (compute-hmac-sha1 password data-to-sign)]"]
  (spit "src/datachannel/stun.clj" (str/replace content target new-target)))
