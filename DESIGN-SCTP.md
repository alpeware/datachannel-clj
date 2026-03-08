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

## 7. Next Phase: MTU, Bundling, and Reliability

### 7.1 MTU & Packetization (Chunk Bundling)
To support efficient network usage and respect path constraints, a new pure pipeline phase called `packetize` will be introduced.
- The core will NOT perform Path MTU Discovery (PMTUD) on the socket. The Usable MTU is passed into the state map externally as `:mtu` (defaulting to 1200).
- State transitions (like `handle-event` or `handle-sctp-packet`) should push pending chunks into internal vectors (e.g., `:pending-control-chunks` and `:tx-queue`), rather than outputting raw packets immediately.
- A `packetize` function will run at the end of the state machine tick. It greedily builds packets up to the `:mtu` byte limit.
- Control chunks (`COOKIE-ECHO`, `SACK`, `HEARTBEAT`) always have strict priority and are bundled first. Data chunks are added until the MTU is reached. Large data chunks exceeding the MTU are fragmented with `B` and `E` flags.

### 7.2 Stream Multiplexing & Priority
To support WebRTC Data Channel multiplexing, SCTP streams must be managed individually in the pure state map.
- Replace the single flat `:tx-queue` with a map of per-stream queues (e.g., `{:streams {0 {:queue [...] :priority 256}, 1 {:queue [...] :priority 128}}}`).
- The `packetize` phase must implement a scheduler (e.g., Weighted Round Robin or Strict Priority) to pull chunks from these stream queues when filling a packet.

### 7.3 Data Channel Reliability (PR-SCTP) & Ordering
To fully support WebRTC Data Channel parameters (RFC 3758 / RFC 6525), the core must handle partial reliability and ordering semantics.
- **Ordering:** The `U` (Unordered) bit is set on `DATA` chunks for unordered channels, bypassing Stream Sequence Number (SSN) incrementing and strict reassembly.
- **Reliability (Partial Reliability):** Properties like `:max-retransmits` and `:max-packet-life` are attached to outbound messages.
- The state machine will drop expired chunks from the transmit queues and emit `FORWARD-TSN` control chunks to instruct the remote peer to advance its cumulative TSN, preventing head-of-line blocking for unreliable channels.

Throughout this phase, the design maintains our strict functional purity (no threads, no `core.async`, no I/O). The pipeline continues to simply map inputs to `{:new-state ... :network-out [...] :app-events [...]}`.

## 8. Inbound Reassembly & Delivery

To mirror the outbound `packetize` pipeline, inbound `DATA` chunks must be processed through a strict `reassemble` phase to handle IP fragmentation and Stream Sequence Number (SSN) ordering.

### 8.1 The Reassembly Queue (`:recv-queue`)
When `handle-sctp-packet` processes a `DATA` chunk, it does NOT immediately emit an `:on-message` event. Instead, it places the chunk into the specific stream's `:recv-queue`.

### 8.2 The `reassemble` Pipeline
After processing all chunks in an inbound packet, the core will execute a `reassemble` pass over the updated streams.
- **Fragment Matching:** It scans the `:recv-queue` for a contiguous sequence of TSNs starting with a chunk marked with the `B` (Begin) flag and ending with the `E` (End) flag.
- **Ordering (Ordered vs Unordered):** - If the `U` (Unordered) bit is set, the assembled message is immediately emitted as an `:on-message` effect and removed from the queue.
  - If the `U` bit is NOT set, the message is only emitted if its SSN exactly matches the stream's expected `:next-ssn`. If it is a future SSN, it remains buffered in the `:recv-queue` until the missing SSN arrives (Head-of-Line blocking).

## 9. Congestion Control (CC)

To prevent network flooding, `datachannel-clj` must implement standard SCTP Congestion Control, throttling the `packetize` pipeline.

### 9.1 CC State Variables
The state map tracks:
- `:cwnd`: Congestion Window (starts at `min(4 * MTU, max(2 * MTU, 4380))`).
- `:ssthresh`: Slow Start Threshold (starts at `:peer-rwnd`).
- `:flight-size`: Total bytes of unacknowledged `DATA` chunks currently on the wire.

### 9.2 Throttling `packetize`
The `packetize` function must respect the `cwnd`. It may only bundle and transmit new `DATA` chunks if `(+ :flight-size chunk-size) <= :cwnd`. If the window is full, chunks remain buffered in the `:streams` queues.

### 9.3 Window Management
- **Slow Start:** When a `SACK` acknowledges new data and `:cwnd <= :ssthresh`, increase `:cwnd` by the number of newly acknowledged bytes (capped at MTU).
- **Congestion Avoidance:** When `:cwnd > :ssthresh`, increase `:cwnd` by `1 * MTU` per RTT.
- **Packet Loss (T3-rtx expiration or Fast Retransmit):** Drop `:ssthresh` to `max(:cwnd / 2, 4 * MTU)`, and reset `:cwnd` to `1 * MTU` (if timeout) or `:ssthresh` (if Fast Retransmit).
