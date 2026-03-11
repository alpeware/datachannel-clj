(ns fix-core
  (:require [clojure.string :as str]))

(let [content (slurp "src/datachannel/core.clj")
      target "(update :app-events conj {:type :dtls-handshake-progress})))\n                    {:new-state state :network-out (vec packets) :app-events [{:type :dtls-handshake-progress}]}))\n                (catch Exception _\n                  {:new-state state :network-out [] :app-events []})))))"
      new-target "(update :app-events conj {:type :dtls-handshake-progress})))\n                    {:new-state state :network-out (vec packets) :app-events [{:type :dtls-handshake-progress}]}))\n                (catch Exception e\n                  (println \"DTLS HANDSHAKE EXCEPTION\" (.getMessage e))\n                  {:new-state state :network-out [] :app-events []})))))"]
  (spit "src/datachannel/core.clj" (str/replace content target new-target)))
