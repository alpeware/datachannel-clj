(require '[clojure.test :as t])
(require '[datachannel.sctp-establish-simultaneous-lost-data-test])
(let [res (t/run-tests 'datachannel.sctp-establish-simultaneous-lost-data-test)]
  (System/exit (+ (:fail res) (:error res))))
