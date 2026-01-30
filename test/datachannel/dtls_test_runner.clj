(ns datachannel.dtls-test-runner
  (:require [clojure.test :as test]
            [datachannel.dtls-test]))

(defn -main []
  (let [{:keys [fail error]} (test/run-tests 'datachannel.dtls-test)]
    (if (> (+ fail error) 0)
      (System/exit 1)
      (System/exit 0))))
