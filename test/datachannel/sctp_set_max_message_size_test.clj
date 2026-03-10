(ns datachannel.sctp-set-max-message-size-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest set-max-message-size-test
  (testing "Can set max message size"
    (let [state (core/create-connection {} true)
          res (core/set-max-message-size state 100000)
          new-state (:new-state res)]
      (is (= 100000 (:max-message-size new-state))))))
