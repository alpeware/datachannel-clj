(ns datachannel.test-runner
  (:require [clojure.test :as test]
            [datachannel.sctp-test]
            [datachannel.dtls-test]
            [datachannel.integration-test]))

(defn -main []
  (let [{:keys [fail error]} (test/run-tests 'datachannel.sctp-test
                                             'datachannel.dtls-test
                                             'datachannel.integration-test)]
    (if (> (+ fail error) 0)
      (System/exit 1)
      (System/exit 0))))
