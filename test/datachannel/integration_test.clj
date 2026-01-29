(ns datachannel.integration-test
  (:require [clojure.test :refer :all]
            [datachannel.core :as dc]))

(deftest test-echo-integration
  (let [port (+ 15000 (rand-int 1000)) ;; Randomize port slightly
        server-received (promise)
        client-received (promise)
        client-connected (promise)

        ;; Start server
        server (dc/listen port)
        ;; Start client
        client (dc/connect "127.0.0.1" port)]

    (reset! (:on-message server)
            (fn [msg]
              (let [s (String. msg "UTF-8")]
                (deliver server-received s)
                (dc/send-msg server "World"))))

    (reset! (:on-message client)
            (fn [msg]
              (let [s (String. msg "UTF-8")]
                (deliver client-received s))))

    (reset! (:on-open client)
            (fn []
              (deliver client-connected true)
              (dc/send-msg client "Hello")))

    (try
      ;; Wait for connection
      (is (deref client-connected 10000 false) "Client failed to connect")

      ;; Wait for messages
      (is (= "Hello" (deref server-received 10000 :timeout)))
      (is (= "World" (deref client-received 10000 :timeout)))

      (catch Exception e
        (is false (str "Exception during integration test: " (.getMessage e)))))))
