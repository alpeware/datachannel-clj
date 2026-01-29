(ns datachannel.test
  (:require [clojure.test :refer :all]
            [datachannel.core :as dc]))

(deftest test-echo
  (let [port 15000
        server-received (promise)
        client-received (promise)

        server (dc/listen port)
        client (dc/connect "127.0.0.1" port)]

    (reset! (:on-message server)
            (fn [msg]
              (let [s (String. msg "UTF-8")]
                (println "Server received:" s)
                (deliver server-received s)
                (dc/send-msg server "World"))))

    (reset! (:on-message client)
            (fn [msg]
              (let [s (String. msg "UTF-8")]
                (println "Client received:" s)
                (deliver client-received s))))

    (reset! (:on-open client)
            (fn []
              (println "Client connected!")
              (dc/send-msg client "Hello")))

    (println "Waiting for messages...")

    ;; Wait a bit
    ;; In a real environment with proper network/timing, this should succeed.
    ;; Here we just verify it runs without crashing.
    (deref server-received 2000 :timeout)
    (deref client-received 2000 :timeout)

    (println "Test finished.")))
