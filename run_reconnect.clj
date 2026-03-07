(require '[clojure.test :as t])
(require '[datachannel.sctp-reconnect-test])
(let [res (t/run-tests 'datachannel.sctp-reconnect-test)]
  (System/exit (+ (:fail res) (:error res))))
