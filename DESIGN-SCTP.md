# SCTP Implementation Design (Sans-IO)

This document outlines the architectural design for the next evolution of our SCTP implementation. To achieve high reliability and deterministic testability, we are adopting a **"Sans-IO"** pattern, inspired by the Rust `sctp-proto` crate.

## 1. Architectural Principles

### 1.1 Functional Purity
The core SCTP state machine is a pure function. It does not perform network I/O, does not read the system clock, and does not use atoms or shared mutable state.

### 1.2 Determinism
The state machine's output is strictly a function of its current state and an input event. Time is treated as an input.

### 1.3 Side-Effects as Data
When the state machine needs to perform an action (sending a packet, setting a timer), it returns that action as a data structure ("Effect") to be executed by the outer IO boundary.

---

## 2. Data Structures (EDN Schema)

The connection state will be expanded to support robust transport features.

### 2.1 Connection State Map
```clojure
{:state             :established ; :closed, :cookie-wait, :cookie-echoed, :established, :shutdown-pending, etc.
 :local-tag         0x12345678
 :remote-tag        0x87654321
 :next-tsn          1000        ; Next TSN to assign to outgoing DATA chunks
 :cum-tsn-ack       999         ; Highest TSN acknowledged by peer
 :remote-tsn        2000        ; Highest TSN received from peer (Cumulative TSN Ack)
 :a-rwnd            102400      ; Advertised receiver window
 :peer-rwnd         102400      ; Peer's advertised receiver window
 :ssthresh          65535       ; Slow start threshold
 :partial-bytes-acked 0         ; For congestion control
 :flight-size       0           ; Bytes currently in flight

 ;; Retransmission Queue
 :tx-queue          []          ; Vector of {:tsn tsn :packet packet :sent-at ms :retries n}

 ;; Timer Registry
 :timers            {:t1-init    {:expires-at 1625000000 :delay 3000}
                     :t3-rtx     nil
                     :t-heartbeat {:expires-at 1625030000 :delay 30000}
                     :delayed-sack {:expires-at nil :delay 200}}

 ;; Stream Registry
 :streams           {0 {:id 0
                        :state :open
                        :priority 1
                        :send-queue [] ; Buffered messages awaiting TSN assignment
                        :next-ssn 0
                        :recv-queue []}} ; Fragments awaiting reassembly

 ;; Metrics
 :metrics           {:tx-packets 0
                     :rx-packets 0
                     :tx-bytes   0
                     :rx-bytes   0
                     :retransmissions 0
                     :unacked-data 0}}
```

### 2.2 Timer Definitions
Timers are managed as absolute timestamps (in milliseconds) within the state.
- **T1-init**: For INIT/COOKIE-ECHO retransmission.
- **T3-rtx**: For DATA chunk retransmission.
- **Delayed SACK**: To delay SACK transmission by up to 200ms.
- **T-heartbeat**: For path liveliness.
- **Shutdown Timers**: T2-shutdown.

---

## 3. Pure Function Signatures

The core logic resides in a single entry point that handles all external stimuli.

### 3.1 `handle-event`
Processes inbound packets or user-initiated requests (e.g., sending data).

```clojure
(defn handle-event [state event now]
  ;; state: Current connection map
  ;; event: {:type :packet :data sctp-packet} OR {:type :send :stream-id 0 :payload bytes}
  ;; now: Current timestamp in ms
  ;; returns -> {:new-state state :effects [effect1 effect2 ...]})
```

### 3.2 `handle-timeout`
Processes timer expirations.

```clojure
(defn handle-timeout [state timer-id now]
  ;; timer-id: :t3-rtx, :delayed-sack, etc.
  ;; returns -> {:new-state state :effects [...]})
```

---

## 4. Effects System

Effects are data descriptions of work to be performed by the Shell.

```clojure
;; Examples of effects:
{:type :send-packet :packet {...}}
{:type :on-message  :stream-id 0 :payload bytes}
{:type :on-error    :cause :max-retransmissions}
{:type :on-open}
```

---

## 5. The Outer Boundary (The Shell)

The Shell is responsible for the bridge between the pure core and the OS.

1.  **Event Loop**: Runs the `Selector` and manages a priority queue of timers.
2.  **Clock**: Provides the `now` timestamp to the core.
3.  **IO Execution**:
    - When `handle-event` or `handle-timeout` returns effects, the Shell iterates through them.
    - `:send-packet` -> Encodes the packet and sends via `DatagramChannel`.
    - `:on-message` -> Invokes the user-provided callback.
4.  **Timer Management**:
    - The Shell inspects the `:timers` in the `new-state` after every call.
    - It schedules system-level wakeups for the earliest `:expires-at` timestamp.

---

## 6. Specific Feature Implementation Logic

### 6.1 Retransmission (T3-rtx)
- When DATA is sent, if `:tx-queue` was empty, start `T3-rtx`.
- On SACK receipt: Remove acked TSNs from `:tx-queue`. If still non-empty, restart `T3-rtx`. If empty, stop `T3-rtx`.
- On `T3-rtx` timeout: Double RTO (backoff), retransmit the first unacked TSN, and restart timer.

### 6.2 Fast Retransmit
- Maintain a duplicate SACK counter.
- On 3rd duplicate SACK, immediately retransmit the missing TSN without waiting for `T3-rtx`.

### 6.3 Stream Prioritization
- The Shell or the `handle-event` (:send) logic will consult the `:streams` configuration.
- A Round-Robin or Weighted-Fair-Queuing scheduler will pull messages from the `:send-queue` of active streams based on their `:priority`.

### 6.4 Delayed SACK
- Instead of sending SACK immediately on DATA receipt, set the `:delayed-sack` timer.
- If another DATA chunk arrives before the timer expires, send SACK immediately and cancel timer.
- If timer expires, send SACK.
