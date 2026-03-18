(ns datachannel.sctp-verification-tag-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.core :as core]))

(deftest rejects-invalid-verification-tag
  (let [state (core/create-connection {} false)
        ;; Create a mock connected state
        state (assoc state :state :established
                     :remote-ver-tag 12345
                     :local-ver-tag 67890
                     :dtls-verified? true)

        ;; valid packet
        packet-valid {:src-port 5000 :dst-port 5000
                      :verification-tag 67890
                      :chunks [{:type :data :tsn 1 :stream-id 0 :seq-num 0 :payload (byte-array 10)}]}

        ;; invalid packet (wrong ver-tag)
        packet-invalid {:src-port 5000 :dst-port 5000
                        :verification-tag 99999
                        :chunks [{:type :data :tsn 2 :stream-id 0 :seq-num 1 :payload (byte-array 10)}]}

        ;; invalid packet (ver-tag 0 for non-init)
        packet-invalid-zero {:src-port 5000 :dst-port 5000
                             :verification-tag 0
                             :chunks [{:type :data :tsn 2 :stream-id 0 :seq-num 1 :payload (byte-array 10)}]}

        ;; init packet with valid tag (must be 0)
        packet-init-valid {:src-port 5000 :dst-port 5000
                           :verification-tag 0
                           :chunks [{:type :init :init-tag 54321} ]}

        ;; init packet with invalid tag (must be 0)
        packet-init-invalid {:src-port 5000 :dst-port 5000
                             :verification-tag 123
                             :chunks [{:type :init :init-tag 54321} ]}]

    (testing "accepts valid verification tag"
      (let [res (core/handle-sctp-packet state packet-valid 0)]
        ;; State should have processed the chunk, maybe updated remote-tsn or queued data
        (is (= 1 (get-in res [:new-state :remote-tsn])))))

    (testing "rejects invalid verification tag"
      (let [res (core/handle-sctp-packet state packet-invalid 0)]
        ;; State should ignore the packet completely
        (is (= -1 (get-in res [:new-state :remote-tsn] -1)))
        (is (= state (:new-state res)))))

    (testing "rejects verification tag 0 for non-init chunks"
      (let [res (core/handle-sctp-packet state packet-invalid-zero 0)]
        (is (= -1 (get-in res [:new-state :remote-tsn] -1)))
        (is (= state (:new-state res)))))

    (testing "accepts INIT chunk with verification tag 0"
      (let [res (core/handle-sctp-packet state packet-init-valid 0)]
        ;; It should have processed the INIT
        (is (= 54321 (get-in res [:new-state :remote-ver-tag])))))

    (testing "rejects INIT chunk with non-zero verification tag"
      (let [res (core/handle-sctp-packet state packet-init-invalid 0)]
        ;; It should ignore the packet completely
        (is (= 12345 (get-in res [:new-state :remote-ver-tag] -1)))
        (is (= state (:new-state res)))))))
