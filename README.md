# datachannel-clj

A pure Clojure implementation of WebRTC Data Channels (SCTP over DTLS over UDP). This library provides a minimal, dependency-free (except for Clojure itself) way to establish peer-to-peer data channels.

## Sans-IO Architecture

`datachannel-clj` is built using a strict **"Sans-IO"** architecture. It embraces the **"Bring Your Own Loop" (BYOL)** philosophy.

The core state machine is completely pure and deterministic:
- It **spawns no threads** or background processes.
- It **performs no network I/O**.
- It **does not read the system clock**.
- It is devoid of mutable callback atoms.

Instead, the library provides pure functions that take the current connection state, an input event (network data or a timer expiration), and the current time as explicit arguments. It returns a map containing the fully updated pure-data state, any outgoing network bytes to transmit, and application events to process.

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

Here is a pseudo-code example of how you might wrap the pure core inside an imperative event loop:

```clojure
(require '[datachannel.core :as dc])

(defn run-event-loop [socket]
  ;; 1. Initialize the pure data state
  (let [initial-state (dc/create-connection {:heartbeat-interval 30000} false)
        conn-state (atom @(:state (:connection initial-state)))]

    (while true
      (let [now-ms (System/currentTimeMillis)]

        ;; 2. Handle pending timers
        (let [timers (:timers @conn-state)]
          (doseq [[timer-id timer] timers]
            (when (>= now-ms (:expires-at timer))
              (let [result (dc/handle-timeout @conn-state timer-id now-ms)]
                (reset! conn-state (:new-state result))
                (process-effects! socket result)))))

        ;; 3. Poll Network I/O
        (when-let [inbound-packet (read-from-socket socket)]
          (let [result (dc/handle-sctp-packet @conn-state inbound-packet now-ms)]
            (reset! conn-state (:new-state result))
            (process-effects! socket result)))))))

(defn- process-effects! [socket {:keys [network-out app-events]}]
  ;; Transmit outgoing bytes
  (doseq [packet network-out]
    (send-to-socket socket packet))

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