(require '[clojure.test :as t])
(require '[datachannel.sctp-error-chunk-test])
(let [res (t/run-tests 'datachannel.sctp-error-chunk-test)]
  (System/exit (+ (:fail res) (:error res))))
