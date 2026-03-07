(require '[clojure.test :as t])
(require '[datachannel.sctp-timeout-resends-packet-test])
(let [res (t/run-tests 'datachannel.sctp-timeout-resends-packet-test)]
  (System/exit (+ (:fail res) (:error res))))
