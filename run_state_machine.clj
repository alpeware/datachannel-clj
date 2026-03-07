(require '[clojure.test :as t])
(require '[datachannel.sctp-state-machine-test])
(let [res (t/run-tests 'datachannel.sctp-state-machine-test)]
  (System/exit (+ (:fail res) (:error res))))
