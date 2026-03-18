(ns datachannel.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [datachannel.api :as api]))

(deftest set-max-message-size-api-test
  (testing "Calling set-max-message-size! successfully mutates the state"
    (let [node (api/create-node {})
          started-node (api/start! node {:ip "127.0.0.1" :port 5002} {})]
      (try
        (let [st @(:state-atom started-node)]
          (is (not (:max-message-size st)))
          (api/set-max-message-size! started-node 1000)
          (let [st2 @(:state-atom started-node)]
            (is (= 1000 (:max-message-size st2)))))
        (finally
          (api/close! started-node))))))

(deftest api-shell-test
  (testing "Shell completely abstracts the BYOL loop for two nodes"
    (let [node-a (api/create-node {:port 5001 :setup "active"})
          node-b (api/create-node {:port 5002 :setup "passive"})

          ;; Extract remote params to cross-feed
          remote-a {:ip "127.0.0.1"
                    :port 5002
                    :fingerprint (:fingerprint (:cert-data node-b))
                    :remote-ice-ufrag (:ufrag (:ice-creds node-b))
                    :remote-ice-pwd (:pwd (:ice-creds node-b))}
          remote-b {:ip "127.0.0.1"
                    :port 5001
                    :fingerprint (:fingerprint (:cert-data node-a))
                    :remote-ice-ufrag (:ufrag (:ice-creds node-a))
                    :remote-ice-pwd (:pwd (:ice-creds node-a))}

          ;; Observers
          a-open? (atom false)
          b-open? (atom false)
          a-msgs (atom [])
          b-msgs (atom [])

          cb-a {:on-open (fn [evt] (when-not (:channel-id evt) (reset! a-open? true)))
                :on-message #(swap! a-msgs conj %)}
          cb-b {:on-open (fn [evt] (when-not (:channel-id evt) (reset! b-open? true)))
                :on-message #(swap! b-msgs conj %)}]

      (try
        (api/start! node-a remote-a cb-a)
        (api/start! node-b remote-b cb-b)

        ;; Wait up to 5s for connection to establish
        (loop [i 0]
          (when (and (< i 100) (not (and @a-open? @b-open?)))
            (Thread/sleep 100)
            (recur (inc i))))

        (is @a-open? "Node A should be open")
        (is @b-open? "Node B should be open")

        (when (and @a-open? @b-open?)
          ;; Send a message from A to B
          (let [channel-id (api/create-data-channel! node-a "gossip" {:ordered false :max-retransmits 0})]
            (api/send! node-a "Hello from A!" channel-id))

          ;; Wait up to 1s for B to receive message
          (loop [i 0]
            (when (and (< i 10) (empty? @b-msgs))
              (Thread/sleep 100)
              (recur (inc i))))

          (is (= 1 (count @b-msgs)) "Node B should have received a message")
          (let [msg-evt (first @b-msgs)]
            (is (= "Hello from A!" (String. (:payload msg-evt) "UTF-8")) "Message content should match")))

        (finally
          (api/close! node-a)
          (api/close! node-b))))))
