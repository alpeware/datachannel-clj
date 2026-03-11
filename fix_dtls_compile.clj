(ns fix-dtls-compile
  (:require [clojure.string :as str]))

(let [content (slurp "src/datachannel/core.clj")
      target "(update :app-events conj {:type :dtls-handshake-progress})))\n                    (do\n                      (when (and app-data (pos? (alength app-data)))\n                        (println \"HANDSHAKE APP DATA WAS DROPPED!\" (alength app-data)))\n                      {:new-state state :network-out (vec packets) :app-events [{:type :dtls-handshake-progress}]}))"
      new-target "(update :app-events conj {:type :dtls-handshake-progress})))\n                    {:new-state state :network-out (vec packets) :app-events [{:type :dtls-handshake-progress}]}))"]
  (spit "src/datachannel/core.clj" (str/replace content target new-target)))
