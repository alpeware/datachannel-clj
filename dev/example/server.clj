(ns example.server
  (:require [datachannel.api :as api]
            [datachannel.dtls :as dtls]
            [datachannel.sdp :as sdp])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(defn -main
  "Main entry point for the example WebRTC server."
  [& _args]
  (println "Starting example WebRTC datachannel server...")

  (let [port 5005
        http-port 8085
        ice-creds (sdp/generate-ice-credentials)
        cert-data (dtls/generate-cert)

        ;; Start the WebRTC UDP listener
        _listener-node
        (api/listen! {:port port
                      :ice-creds ice-creds
                      :cert-data cert-data}
                     {:on-connection
                      (fn [node]
                        (println "\n[WebRTC] New incoming connection attempt!")
                        (api/set-callbacks! node
                                            {:on-open
                                             (fn [_]
                                               (println "[WebRTC] Data channel opened!"))

                                             :on-message
                                             (fn [msg]
                                               (let [payload (if (:is-string? msg)
                                                               (String. ^bytes (:payload msg) "UTF-8")
                                                               (String. ^bytes (:payload msg) "UTF-8"))
                                                     channel-id (:stream-id msg)
                                                     protocol (:protocol msg)]
                                                 (println "[WebRTC] Received chunk:" payload "on stream" channel-id "protocol" protocol)
                                                 (let [reply (str "Pong: " payload)]
                                                   (println "[WebRTC] Sending reply:" reply)
                                                   (api/send! node reply channel-id))))

                                             :on-error
                                             (fn [err]
                                               (println "[WebRTC] Error:" err))

                                             :on-close
                                             (fn [_]
                                               (println "[WebRTC] Connection closed."))}))})

        ;; Generate the Server's SDP to be offered as Answer to the JS client
        base-sdp (sdp/format-answer {:local-ip "127.0.0.1"
                                     :port port
                                     :ice-ufrag (:ufrag ice-creds)
                                     :ice-pwd (:pwd ice-creds)
                                     :fingerprint (:fingerprint cert-data)
                                     :setup "passive"
                                     :ice-lite? true})
        server-sdp (str base-sdp "a=candidate:1 1 UDP 2130706431 127.0.0.1 " port " typ host\r\n")

        ;; Start the HTTP server to serve the client JS
        http-server (HttpServer/create (InetSocketAddress. http-port) 0)]

    (.createContext http-server "/"
                    (proxy [HttpHandler] []
                      (handle [^HttpExchange exchange]
                        (try
                          (let [method (.getRequestMethod exchange)]
                            (if (= method "GET")
                              (let [html (slurp "dev/example/index.html")
                                    ;; Inject the server's static SDP into the HTML template
                                    response (.replace html "SERVER_SDP_PLACEHOLDER" server-sdp)
                                    bytes (.getBytes response "UTF-8")]
                                (.sendResponseHeaders exchange 200 (alength bytes))
                                (with-open [os (.getResponseBody exchange)]
                                  (.write os bytes)))
                              (do
                                (.sendResponseHeaders exchange 405 -1)
                                (.close exchange))))
                          (catch Exception e
                            (println "HTTP Error:" (.getMessage e))
                            (.sendResponseHeaders exchange 500 -1)
                            (.close exchange))))))

    (.setExecutor http-server nil)
    (.start http-server)

    (println (str "\nServer started!\n"
                  "1. WebRTC listener listening on UDP port " port "\n"
                  "2. HTTP server running. Open http://localhost:" http-port " in your browser.\n\n"
                  "Waiting for connections..."))

    ;; Keep the main thread alive
    @(promise)))
