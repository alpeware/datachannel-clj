# datachannel-clj

A pure Clojure implementation of WebRTC Data Channels (SCTP over DTLS over UDP). This library provides a minimal, dependency-free (except for Clojure itself) way to establish peer-to-peer data channels.

## Sans-IO Architecture

`datachannel-clj` is built using a strict **"Sans-IO"** architecture. It embraces the **"Bring Your Own Loop" (BYOL)** philosophy.

The core state machine (`datachannel.core`) is completely pure and deterministic:
- It **spawns no threads** or background processes.
- It **performs no network I/O**.
- It **does not read the system clock**.
- It is devoid of mutable callback atoms.

Instead, the library provides pure functions that take the current connection state, an input event (network data or a timer expiration), and the current time as explicit arguments. It returns a map containing the fully updated pure-data state, any outgoing network bytes to transmit, and application events to process.

While the core is pure, the library includes `datachannel.nio` to provide the boilerplate Java NIO OS-level primitives (such as `DatagramChannel` and `Selector`) needed to actually route packets to the network.

This functional purity makes the library extremely resilient, entirely deterministic for testing, and adaptable to any environment (async I/O, blocking I/O, game loops, or simulated networks).

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

## Core Concepts & Usage

The primary interaction with the library happens through three core pure functions:

1. `handle-sctp-packet [state packet now-ms]` - Processes an incoming parsed SCTP packet.
2. `handle-timeout [state timer-id now-ms]` - Processes the expiration of a connection timer.
3. `handle-event [state event now-ms]` - Handles external user requests, like sending data or gracefully shutting down.

Every function returns a standardized map:
```clojure
{:new-state   {...}        ;; The completely updated connection state map
 :network-out [...]        ;; A vector of byte arrays or packet maps to send over the network
 :app-events  [...]}       ;; A vector of events (e.g. :on-open, :on-message) to be handled by your application
```

### Example: High-Level API

Here is a concise, elegant example of how a consumer uses `datachannel.api` to establish a connection.

```clojure
(require '[datachannel.api :as api])

(defn start-peer []
  ;; 1. Prepare the node
  (let [node (api/create-node {:port 5000 :setup "active"})

        ;; Example remote connection info (would typically come from signaling)
        remote-sdp {:ip "127.0.0.1" :port 5001}

        ;; Callbacks definition
        callbacks {:on-open (fn []
                              (println "Connection established!")
                              ;; Create a data channel compliant with the W3C spec
                              (let [channel-id (api/create-data-channel! node "gossip" {:ordered false :max-retransmits 0})]
                                ;; Send data over the newly created channel
                                (api/send! node "Hello, WebRTC!" channel-id)))

                   :on-message (fn [evt]
                                 (if (:is-string? evt)
                                   (println "Received string on channel" (:stream-id evt) ":"
                                            (String. ^bytes (:payload evt) "UTF-8"))
                                   (println "Received binary data on channel" (:stream-id evt))))

                   :on-buffered-amount-high (fn [evt]
                                              (println "High water mark reached on stream" (:stream-id evt) "... Pausing sends!"))

                   :on-buffered-amount-low (fn [evt]
                                             (println "Buffer cleared on stream" (:stream-id evt) "... Resuming sends!"))

                   :on-ice-candidate (fn [evt]
                                       (println "New ICE Candidate discovered:" (:candidate evt)))

                   :on-ice-gathering-state-change (fn [evt]
                                                    (println "ICE Gathering State:" (:gathering-state evt)))

                   :on-ice-connection-state-change (fn [evt]
                                                     (println "ICE Connection State:" (:connection-state evt)))

                   :on-closing (fn [_]
                                 (println "Connection is closing..."))

                   :on-error (fn [evt]
                               (println "Connection error:" (:cause evt)))

                   :on-close (fn [_]
                               (println "Connection closed."))}]

    ;; 2. Start the connection loop
    (api/start! node remote-sdp callbacks)))
```

*Note: While this high-level API handles the NIO background loop and state synchronization for you, the underlying `datachannel.core` that powers it remains completely purely functional and strictly BYOL (Bring Your Own Loop).*

## Use Cases

Because of its lightweight, deterministic, and functionally pure nature, `datachannel-clj` is highly suitable for:

* **Constrained Environments:** Embedding WebRTC data channels where custom threading models or zero-allocation paths are required.
* **Deterministic Testing:** Simulating network drops, latency, and timer expirations in pure unit tests without flaky `Thread/sleep` or race conditions.
* **AI Agents:** Serving as the robust peer-to-peer networking backbone for autonomous AI agents that require tightly controlled, predictable execution loops.

## Running Tests

To run the full test suite, use the following command (requires Clojure CLI):

```bash
clojure -M:test -m datachannel.test-runner
```