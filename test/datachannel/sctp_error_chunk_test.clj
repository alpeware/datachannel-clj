(ns datachannel.sctp-error-chunk-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(deftest receiving-error-chunk-reports-as-callback-test
  (testing "Receiving Error Chunk Reports As Callback"
    (let [state-atom (atom {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :established})
          out-queue (java.util.concurrent.LinkedBlockingQueue.)
          error-called (atom false)
          received-causes (atom nil)
          on-error (atom (fn [causes]
                           (reset! error-called true)
                           (reset! received-causes causes)))
          conn {:state state-atom :sctp-out out-queue }


          error-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                        :chunks [{:type :error
                                  :causes [{:cause-code 1 :chunk-data (byte-array 0)}]}]}]

      (let [{:keys [app-events]} (@#'core/handle-sctp-packet @state-atom error-packet (System/currentTimeMillis))
            evt (first app-events)]
        (when (= (:type evt) :on-error)
          (reset! error-called true)
          (reset! received-causes (:causes evt))))

      (is @error-called "The :on-error callback should have been called")
      (is (= 1 (count @received-causes)) "There should be one error cause")
      (is (= 1 (:cause-code (first @received-causes))) "The cause code should match the received cause code"))))
