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

### Example: "Bring Your Own Loop"

Here is a realistic example of how you might wrap the pure core inside a Java NIO polling loop using our `datachannel.nio` helpers:

```clojure
(require '[datachannel.core :as dc]
         '[datachannel.nio :as nio])
(import '[java.nio ByteBuffer])

(defn run-event-loop [port remote-host remote-port]
  ;; 1. Initialize NIO resources
  (let [channel  (nio/create-non-blocking-channel port)
        selector (nio/create-selector)]
    (nio/connect-channel channel remote-host remote-port)
    (nio/register-for-read channel selector)

    ;; 2. Initialize the pure data state
    (let [initial-state (dc/create-connection {:heartbeat-interval 30000} false)
          conn-state (atom (:connection initial-state))
          buffer (ByteBuffer/allocate 2048)]

      (while true
        (let [now-ms (System/currentTimeMillis)]

          ;; 3. Handle pending timers
          (let [timers (:timers @conn-state)]
            (doseq [[timer-id timer] timers]
              (when (>= now-ms (:expires-at timer))
                (let [raw-result (dc/handle-timeout @conn-state timer-id now-ms)
                      result (dc/serialize-network-out raw-result buffer)]
                  (reset! conn-state (:new-state result))
                  ;; Note: In a real app you'd store the remote-addr for the peer
                  (process-effects! channel result (java.net.InetSocketAddress. remote-host remote-port))))))

          ;; 4. Poll Network I/O
          (.select selector 10) ;; Wait up to 10ms
          (let [keys (.selectedKeys selector)
                key-iter (.iterator keys)]
            (while (.hasNext key-iter)
              (let [key (.next key-iter)]
                (.remove key-iter)
                (when (.isReadable key)
                  (.clear buffer)
                  (when-let [remote-addr (.receive channel buffer)]
                    (.flip buffer)
                    (let [bytes-read (.remaining buffer)
                          payload (byte-array bytes-read)]
                      (.get buffer payload)
                      ;; Pass bytes to the pure core
                      (let [raw-result (dc/handle-receive @conn-state payload now-ms remote-addr)
                            result (dc/serialize-network-out raw-result buffer)]
                        (reset! conn-state (:new-state result))
                        (process-effects! channel result remote-addr)))))))))))))

(defn- process-effects! [channel {:keys [network-out-bytes app-events]} remote-addr]
  ;; Transmit outgoing bytes
  (doseq [packet network-out-bytes]
    (.send channel packet remote-addr))

  ;; React to state changes
  (doseq [event app-events]
    (case (:type event)
      :on-open    (println "Connection established!")
      :on-message (println "Received message:" (String. (:payload event)))
      :on-error   (println "Connection error:" (:cause event))
      :on-close   (println "Connection closed.")
      nil)))
```

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