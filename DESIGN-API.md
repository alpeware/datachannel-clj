# API Redesign (Strict Sans-IO Architecture)

This document details the architectural refactor of the `datachannel-clj` public API to enforce a strict "Sans-IO" paradigm. The primary goals are to remove all imperative callbacks, eliminate atom-mutations within the core state machine, and officially adopt a "Bring Your Own Loop" pattern.

## Critical Architectural Constraints

*   **No Threads or Async Constructs:** The library must not spawn any threads, loops, `future`, `promise`, or `core.async` blocks.
*   **Bring Your Own Loop:** Consumers are strictly responsible for managing their own event loops, threads, and I/O polling mechanisms.
*   **Pure Data State:** The connection state map must be composed entirely of pure data. All callback atoms (e.g., `:on-open`, `:on-message`, `:on-error`, `:on-data`) are completely removed from the state map.

---

## 1. The Pure Core (`datachannel.core`)

The `datachannel.core` namespace will expose pure functions that compute the next state of the connection and produce descriptive effects. Time and incoming bytes are provided as explicit arguments.

### Function Signatures

```clojure
(defn handle-receive
  "Processes incoming network data.
  - `state`: The current connection state map (pure data).
  - `network-bytes`: A byte-array or ByteBuffer containing the UDP payload received.
  - `now-ms`: A deterministic timestamp (in milliseconds).

  Returns a map containing the new state, outgoing bytes, and app events."
  [state network-bytes now-ms]
  ;; ... implementation ...
  )

(defn handle-timeout
  "Processes the expiration of a timer.
  - `state`: The current connection state map (pure data).
  - `timer-id`: The identifier for the timer that expired (e.g., :t1-init, :t3-rtx).
  - `now-ms`: A deterministic timestamp (in milliseconds).

  Returns a map containing the new state, outgoing bytes, and app events."
  [state timer-id now-ms]
  ;; ... implementation ...
  )
```

### Return Map Structure

Both core functions will return a map with the following structure:

```clojure
{:new-state   {...}        ;; The completely updated state map (pure data)
 :network-out [[...]]      ;; A vector of byte arrays (or ByteBuffers) to be sent over the wire
 :app-events  [{...}]}     ;; A vector of application-level events
```

### Application Events (EDN Structure)

The `:app-events` vector contains data descriptions of events that the consumer application needs to handle. The structure is pure EDN.

```clojure
;; Examples of application events:

;; Connection successfully opened
{:type :on-open}

;; Application data received
{:type :on-message
 :stream-id 0
 :protocol :webrtc/string
 :payload #object[... bytes ...]} ;; The actual byte payload

;; A fatal or non-fatal error occurred
{:type :on-error
 :cause :max-retransmissions
 :details "T1-init timer exceeded maximum retries."}

;; Connection was gracefully or abruptly closed
{:type :on-close}
```

---

## 2. The NIO Utilities (`datachannel.nio`)

To assist consumers, we will introduce a new namespace, `datachannel.nio`, containing pure boilerplate helper functions for non-blocking Java NIO.

**Crucially, this namespace will NOT spawn any threads or background loops.** It only sets up the OS-level primitives.

### Proposed API Helpers

```clojure
(ns datachannel.nio
  (:import [java.nio.channels DatagramChannel Selector SelectionKey]
           [java.net InetSocketAddress]))

(defn create-non-blocking-channel
  "Creates and binds a DatagramChannel configured for non-blocking I/O."
  [port]
  ;; ... open channel, bind to port, configure blocking false ...
  )

(defn create-selector []
  "Opens a new NIO Selector."
  (Selector/open))

(defn register-for-read
  "Registers the given channel with the selector for OP_READ operations."
  [^DatagramChannel channel ^Selector selector]
  (.register channel selector SelectionKey/OP_READ))

;; Example signature for connecting a channel
(defn connect-channel [^DatagramChannel channel host port]
  (.connect channel (InetSocketAddress. host port)))
```

---

## 3. Consumer Example ("Bring Your Own Loop")

The following snippet demonstrates how a consumer will integrate the `nio` utilities and feed the `core` state machine within their own application thread.

```clojure
(require '[datachannel.core :as core]
         '[datachannel.nio :as nio])

(defn run-my-app-loop [host port]
  (let [channel  (nio/create-non-blocking-channel 0)
        selector (nio/create-selector)
        _        (nio/connect-channel channel host port)
        _        (nio/register-for-read channel selector)]

    ;; Application manages the mutable state container
    (let [conn-state (atom (core/initial-state ...))]

      ;; The consumer's own thread / event loop
      (while true
        (let [now-ms (System/currentTimeMillis)]

          ;; 1. Check Timers (Pseudo-code for evaluating earliest timer)
          (let [earliest-timer (get-earliest-timer @conn-state)]
            (when (and earliest-timer (>= now-ms (:expires-at earliest-timer)))
              (let [result (core/handle-timeout @conn-state (:id earliest-timer) now-ms)]
                (reset! conn-state (:new-state result))
                (handle-effects! channel result))))

          ;; 2. Poll Network I/O
          (let [ready-channels (.select selector 10)] ;; Timeout of 10ms
            (when (> ready-channels 0)
              (let [keys (.selectedKeys selector)]
                (doseq [key keys]
                  (when (.isReadable key)
                    (let [buffer (read-into-buffer channel)
                          bytes  (buffer-to-byte-array buffer)
                          result (core/handle-receive @conn-state bytes now-ms)]

                      ;; Advance state
                      (reset! conn-state (:new-state result))

                      ;; Process side-effects
                      (handle-effects! channel result))))
                (.clear keys)))))))))

(defn- handle-effects! [channel {:keys [network-out app-events]}]
  ;; Send outgoing network packets
  (doseq [packet-bytes network-out]
    (.write channel (java.nio.ByteBuffer/wrap packet-bytes)))

  ;; Process application events
  (doseq [event app-events]
    (case (:type event)
      :on-open    (println "DataChannel is open!")
      :on-message (println "Received message on stream" (:stream-id event)
                           "payload size:" (alength (:payload event)))
      :on-error   (println "Error occurred:" (:cause event))
      nil)))
```

---

## 4. Testing Migration

Our existing deterministic test suites (e.g., `handshake-test`, `sctp-state-machine-test`) currently rely on injecting mutable callback atoms (like `(atom nil)`) into the connection map and asserting whether those callbacks were fired.

### Migration Strategy

1.  **Remove Callback Atoms:** Update test fixture connections to remove `:on-open`, `:on-message`, etc. The test state will be completely pure.
2.  **Assert on Returned Data:** Instead of verifying if an atom was updated or a mock function was called, the tests will capture the map returned by `core/handle-receive` or `core/handle-timeout`.
3.  **Inspect `:app-events`:** Tests will assert the exact contents of the `:app-events` vector.

**Before (Imperative):**
```clojure
(let [msg-received (atom false)
      conn {:state (atom {...})
            :on-message (atom (fn [msg] (reset! msg-received true)))}]
  (core/handle-sctp-packet packet conn)
  (is @msg-received))
```

**After (Sans-IO):**
```clojure
(let [conn-state {...}
      result (core/handle-receive conn-state packet-bytes (current-time-ms))
      events (:app-events result)]
  (is (some #(= (:type %) :on-message) events))
  ;; Assert exact state transitions deterministically
  (is (= :established (:state (:new-state result)))))
```

This ensures our tests remain completely deterministic, devoid of side-effects, and isolated from network/threading concerns.
