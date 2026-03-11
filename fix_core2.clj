(ns fix-core2
  (:require [clojure.string :as str]))

(let [content (slurp "src/datachannel/core.clj")
      target "     :ssn 0\n     :timers {}\n     :heartbeat-interval"
      new-target "     :ssn 0\n     :timers (if (seq (get options :remote-candidates [])) {:stun/check-candidates {:expires-at 0}} {})\n     :heartbeat-interval"]
  (spit "src/datachannel/core.clj" (str/replace content target new-target)))
