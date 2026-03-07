(require '[clojure.string :as str])
(let [code (slurp "src/datachannel/core.clj")
      new-code (str/replace code
                 #"(?s)\n        next-state \(:new-state res\)\n        next-state \(if \(\:on-message connection\).*?next-state\)"
                 "\n        next-state (:new-state res)\n        next-state (if (:on-message connection) (assoc next-state :on-message (:on-message connection)) next-state)\n        next-state (if (:on-data connection) (assoc next-state :on-data (:on-data connection)) next-state)\n        next-state (if (:on-open connection) (assoc next-state :on-open (:on-open connection)) next-state)\n        next-state (if (:on-error connection) (assoc next-state :on-error (:on-error connection)) next-state)")]
  (spit "src/datachannel/core.clj" new-code))
