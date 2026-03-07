(require '[clojure.test :as t])
(require '[datachannel.sctp-cookie-echo-abort-test])
(let [res (t/run-tests 'datachannel.sctp-cookie-echo-abort-test)]
  (System/exit (+ (:fail res) (:error res))))
