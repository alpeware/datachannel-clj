(ns fix-api
  (:require [clojure.string :as str]))

(let [content (slurp "src/datachannel/api.clj")
      target "                           {:cert-data (:cert-data node)\n                            :ice-ufrag (:ufrag (:ice-creds node))\n                            :ice-pwd (:pwd (:ice-creds node))})"
      new-target "                           {:cert-data (:cert-data node)\n                            :ice-ufrag (:ufrag (:ice-creds node))\n                            :ice-pwd (:pwd (:ice-creds node))\n                            :remote-ice-ufrag (:remote-ice-ufrag remote-sdp-params)\n                            :remote-ice-pwd (:remote-ice-pwd remote-sdp-params)\n                            :remote-candidates (if (and (:ip remote-sdp-params) (:port remote-sdp-params))\n                                                 [{:ip (:ip remote-sdp-params) :port (:port remote-sdp-params)}]\n                                                 [])})"]
  (spit "src/datachannel/api.clj" (str/replace content target new-target)))
