(ns run-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as core]))

(load-file "test/datachannel/sctp_doesnt_send_more_packets_until_cookie_ack_has_been_received_test.clj")

(alter-var-root #'datachannel.sctp-doesnt-send-more-packets-until-cookie-ack-has-been-received-test/doesnt-send-more-packets-until-cookie-ack-has-been-received-test
  (fn [f]
    (fn []
      (binding [*out* *err*]
        (let [now 1000
              client-state {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 1000 :ssn 0 :state :closed}
              server-state {:remote-tsn 0 :remote-ver-tag 0 :next-tsn 2000 :ssn 0 :state :closed}
              client-state-cw (merge client-state {:state :cookie-wait :local-ver-tag 1111})
              init-packet {:src-port 5000 :dst-port 5001 :verification-tag 0
                           :chunks [{:type :init :init-tag 1111 :a-rwnd 100000 :outbound-streams 1 :inbound-streams 1 :initial-tsn 1000 :params {}}]}
              res-sd1 (core/send-data client-state-cw (.getBytes "Early Data 1" "UTF-8") 0 :webrtc/string now)
              client-state-cw1 (:new-state res-sd1)
              res-s1 (@#'core/handle-sctp-packet server-state init-packet now)
              server-state-ia (:new-state res-s1)
              init-ack-packet (first (:network-out res-s1))
              res-c1 (@#'core/handle-sctp-packet client-state-cw1 init-ack-packet now)
              client-state-ce (:new-state res-c1)
              res-sd2 (core/send-data client-state-ce (.getBytes "Early Data 2" "UTF-8") 0 :webrtc/string now)
              client-state-ce2 (:new-state res-sd2)
              res-s2 (@#'core/handle-sctp-packet server-state-ia (first (:network-out res-c1)) now)
              cookie-ack-packet (first (:network-out res-s2))
              res-c2 (@#'core/handle-sctp-packet client-state-ce2 cookie-ack-packet now)]
          (println "network-out res-c1:" (:network-out res-c1))
          (println "network-out res-c2:" (:network-out res-c2))
          )))))
(datachannel.sctp-doesnt-send-more-packets-until-cookie-ack-has-been-received-test/doesnt-send-more-packets-until-cookie-ack-has-been-received-test)
