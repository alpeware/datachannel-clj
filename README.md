# datachannel-clj

A pure Clojure implementation of WebRTC Data Channels (SCTP over DTLS over UDP). This library provides a minimal, dependency-free (except for Clojure itself) way to establish peer-to-peer data channels.

## Installation

Add the following to your `deps.edn`:

```clojure
{:deps {datachannel-clj/datachannel-clj {:git/url "https://github.com/your-username/datachannel-clj"
                                         :git/sha "..."}}}
```

### Required JVM Options

Because this library uses some internal Java APIs for certificate generation, you must add the following `--add-exports` flags to your JVM options:

```bash
--add-exports=java.base/sun.security.tools.keytool=ALL-UNNAMED
--add-exports=java.base/sun.security.x509=ALL-UNNAMED
```

In `deps.edn`, you can add them to an alias:

```clojure
:aliases {:run {:jvm-opts ["--add-exports=java.base/sun.security.tools.keytool=ALL-UNNAMED"
                           "--add-exports=java.base/sun.security.x509=ALL-UNNAMED"]}}
```

## Quick Start

### Server (Listener)

```clojure
(require '[datachannel.core :as dc])

(let [server (dc/listen 15000)]
  (reset! (:on-message server)
          (fn [msg]
            (println "Server received:" (String. msg "UTF-8"))
            (dc/send-msg server "Hello from Server")))

  (reset! (:on-open server)
          (fn []
            (println "Server: Connection opened"))))
```

### Client

```clojure
(require '[datachannel.core :as dc])

(let [client (dc/connect "127.0.0.1" 15000)]
  (reset! (:on-message client)
          (fn [msg]
            (println "Client received:" (String. msg "UTF-8"))))

  (reset! (:on-open client)
          (fn []
            (println "Client: Connection opened")
            (dc/send-msg client "Hello from Client"))))
```

## Advanced Usage

### Manual Certificate Management

For WebRTC integration, you often need to provide the DTLS certificate fingerprint in the SDP. You can generate a certificate and retrieve its fingerprint manually:

```clojure
(require '[datachannel.dtls :as dtls])
(require '[datachannel.core :as dc])

(let [cert-data (dtls/generate-cert)
      fingerprint (:fingerprint cert-data)]
  (println "DTLS Fingerprint:" fingerprint)

  ;; Use the generated cert for the connection
  (let [conn (dc/listen 15000 :cert-data cert-data)]
    ;; ...
    ))
```

### Closing Connections

To gracefully shut down a connection and release resources:

```clojure
(dc/close connection)
```

## Running Tests

To run the full test suite, use the following command (requires Clojure CLI):

```bash
clojure -M:test -m datachannel.test-runner
```

The test suite includes unit tests for SCTP and DTLS, as well as integration tests against a reference Java WebRTC implementation.
