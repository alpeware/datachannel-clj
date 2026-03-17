# Comprehensive Security Audit Report
**Target:** `alpeware-datachannel-clj` (Pure Clojure WebRTC Data Channel Implementation)
**Auditor:** Principal Protocol Architect & Security Engineer
**Date:** March 17, 2026

---

## 1. Executive Summary

A comprehensive security, architecture, and protocol compliance audit was performed on the `alpeware-datachannel-clj` codebase. The library utilizes a pure "Sans-IO" state machine design, which is highly commendable for producing deterministic, testable, and thread-safe logic. By abstracting away concurrency and threading from the core protocol logic, the architecture inherently prevents entire classes of race conditions.

However, the audit revealed **several Critical and High-severity vulnerabilities** at the boundaries where the pure state machine interacts with the physical network (the outer NIO Shell), as well as significant deviations from RFC specifications. 

The most critical vulnerabilities allow unauthenticated remote attackers to bypass DTLS encryption, trivially crash the background network loops via unhandled binary parsing exceptions, and exhaust JVM heap memory through unauthenticated state allocations.

**Immediate remediation of the Critical and High findings is mandatory** before this library can be safely deployed in production or exposed to untrusted public networks.

---

## 2. Detailed Vulnerability Findings

### 2.1 [CRITICAL] Remote DoS via Unhandled Exceptions in Networking Loop
**Location:** `src/datachannel/api.clj` (`listen!` & `start!`), `src/datachannel/dcep.clj` (`decode-message`)
**CWE:** CWE-248 (Uncaught Exception) / CWE-20 (Improper Input Validation)

**Description:** 
In the background `while @running` event loop within `api.clj`, the entire loop is wrapped in a single, outer `try/catch` block. Furthermore, binary decoders deep within the pure core lack bounds checking. For example, `dcep/decode-message` reads `label-len` from the buffer and immediately attempts to allocate and read a byte array of that size:
```clojure
label-len (bit-and (.getShort buf) 0xffff)
label-bytes (byte-array label-len)
_ (.get buf label-bytes)
```

**Impact:** 
If an attacker sends a malformed DCEP packet (or other chunk) with a declared length larger than the remaining buffer, `ByteBuffer` throws a `java.nio.BufferUnderflowException`. Because this exception is unhandled in the pure core, it bubbles up to the outer `try/catch` in `api.clj`, instantly and permanently terminating the entire background thread. This results in a single-packet Denial of Service (DoS) that disconnects all active users and stops the server from processing further I/O.

**Remediation:** 
1. Move the `try/catch` block *inside* the `while @running` loop in `api.clj` to ensure individual packet parsing failures do not kill the thread. 
2. Implement strict bounds checking (e.g., `(> label-len (.remaining buf))`) in all binary decoders (`dcep.clj`, `sctp.clj`) before allocating or reading arrays.

---

### 2.2 [CRITICAL] Complete DTLS Bypass via Raw SCTP over UDP Fallback
**Location:** `src/datachannel/core.clj` (`handle-receive`)
**CWE:** CWE-288 (Authentication Bypass Using an Alternate Path or Channel)

**Description:** 
The network demultiplexer routes incoming UDP datagrams by inspecting the first byte. If the byte matches `0-3`, it is routed to STUN. If it matches `20-63`, it is routed to DTLS. However, any other byte sequence falls into an `:else` block where it is treated as a raw, unencrypted SCTP packet and passed directly to the state machine:
```clojure
;; SCTP (Default, raw)
:else
(let [buf (ByteBuffer/wrap network-bytes)
      packet (sctp/decode-packet buf)]
  (handle-sctp-packet state packet now-ms))
```

**Impact:** 
WebRTC standards (RFC 8261) strictly mandate that all SCTP traffic must be encapsulated within DTLS. Because the raw fallback exists, an active attacker can bypass DTLS encryption and mutual authentication entirely by injecting raw SCTP packets (e.g., sending `ABORT` or `DATA` chunks directly to the UDP port) as long as the first byte of the packet is `>= 64`.

**Remediation:** 
Remove the raw SCTP `:else` fallback block entirely. Any UDP packet that does not match a valid STUN or DTLS signature must be immediately dropped.

---

### 2.3 [CRITICAL] Unbounded UDP Routing Table Memory Exhaustion (OOM)
**Location:** `src/datachannel/api.clj` (`listen!`)
**CWE:** CWE-400 (Uncontrolled Resource Consumption)

**Description:** 
When the multiplexing `listen!` loop receives a packet from an unknown `sender-addr`, it blindly allocates a new `child-node`—which includes generating a fresh RSA certificate (`dtls/generate-cert`), allocating Direct ByteBuffers, and initializing complex state maps—and inserts it into the `@routing-table` atom.

**Impact:** 
An attacker spoofing random UDP source IPs and ports can flood the listener with single packets. The server will rapidly allocate thousands of heavy child nodes and RSA keys per second, exhausting JVM heap memory and triggering a fatal `OutOfMemoryError`.

**Remediation:** 
1. Implement an LRU cache or a background reaper thread that evicts routing table entries that fail to complete the DTLS handshake within a reasonable timeframe (e.g., 5 seconds) or reach the `:closed` state.
2. Delay heavy cryptographic allocations (like RSA key generation) until the remote peer has been verified via STUN.

---

### 2.4 [HIGH] Unauthenticated STUN Reflection and Amplification
**Location:** `src/datachannel/stun.clj` (`handle-packet`)
**CWE:** CWE-345 (Insufficient Verification of Data Authenticity)

**Description:** 
When the STUN agent receives a Binding Request (`msg-type 0x0001`), it immediately generates and returns a Binding Response via `make-binding-response` without validating the `MESSAGE-INTEGRITY` (HMAC-SHA1) attribute of the incoming request against the expected `:ice-pwd`. Furthermore, `core/handle-receive` automatically pivots the active connection routing to the source of the STUN packet.

**Impact:** 
The server acts as an open UDP reflector. Attackers can spoof the source IP of STUN requests, forcing the server to bounce larger authenticated STUN responses to a victim IP (Amplification). Furthermore, an attacker can hijack ICE connectivity checks and map ports without possessing the shared ICE password.

**Remediation:** 
Extract the `MESSAGE-INTEGRITY` attribute from inbound STUN Binding Requests, locally compute the expected HMAC-SHA1 using the `:ice-pwd`, and silently drop the packet if the signatures do not match.

---

### 2.5 [HIGH] Missing SCTP Verification Tag Validation
**Location:** `src/datachannel/core.clj` (`handle-sctp-packet`)
**CWE:** CWE-346 (Origin Validation Error)

**Description:** 
RFC 4960 mandates that the 32-bit `Verification Tag` in the common SCTP header must strictly match the endpoint's expected `local-ver-tag`. The `sctp/decode-packet` function parses the tag, but `handle-sctp-packet` blindly executes the chunks without asserting that the tag matches the connection state.

**Impact:** 
This breaks SCTP's primary defense against blind packet injection. An off-path attacker (or stray packets from old connections) can tear down connections by injecting `ABORT` chunks without needing to guess the correct Verification Tag.

**Remediation:** 
In `handle-sctp-packet`, explicitly verify that `(:verification-tag packet)` matches `(:local-ver-tag state)`. Ensure exceptions are made for `INIT` chunks, which expect a verification tag of `0`.

---

### 2.6 [HIGH] DTLS Certificate Fingerprint Verification TOCTOU Bypass (MitM)
**Location:** `src/datachannel/core.clj` (`handle-receive`)
**CWE:** CWE-295 (Improper Certificate Validation)

**Description:** 
Because WebRTC uses self-signed certificates, cryptographic identity is verified by comparing the remote peer's DTLS certificate fingerprint against the hash passed over the SDP signaling channel. In `handle-receive`:
```clojure
(if (and (:remote-fingerprint state)
         (not (dtls/verify-peer-fingerprint engine (:remote-fingerprint state)))) ...)
```
If `(:remote-fingerprint state)` evaluates to `nil` (due to signaling lag or omission in listener mode), the `and` condition short-circuits. The verification is bypassed, and the state transitions to `(:dtls-verified? true)`. 

**Impact:** 
An active Man-in-the-Middle (MitM) attacker can connect using an arbitrary self-signed certificate. Because the fingerprint check is bypassed, the attacker successfully negotiates the DTLS tunnel, breaking the primary confidentiality guarantee of WebRTC.

**Remediation:** 
Require the presence of `:remote-fingerprint`. If it is `nil` at the end of the DTLS handshake (`FINISHED` state), the connection must be explicitly rejected with a fatal protocol error, or application data must be quarantined until the signaling layer officially provides the fingerprint.

---

### 2.7 [MEDIUM] Unbounded SCTP Receive Queue (Memory Exhaustion)
**Location:** `src/datachannel/chunks.clj` (`process-chunk :data`), `src/datachannel/reassemble.clj`
**CWE:** CWE-770 (Allocation of Resources Without Limits or Throttling)

**Description:** 
Fragmented and out-of-order incoming `DATA` chunks are unconditionally appended to a stream's `:recv-queue`. There is no limit applied to the size of this vector.

**Impact:** 
A malicious peer can initiate a stream and send an infinite number of fragments lacking the `E` (End) flag, forcing the receiver to buffer them indefinitely until memory is exhausted.

**Remediation:** 
Enforce a configurable byte maximum on the `:recv-queue` (derived from the receiver window or application limits). If the limit is exceeded, drop the chunks or abort the association.

---

### 2.8 [MEDIUM] Missing SCTP Checksum Validation
**Location:** `src/datachannel/sctp.clj` (`decode-packet`), `src/datachannel/core.clj` (`handle-sctp-packet`)
**CWE:** CWE-354 (Improper Validation of Integrity Check Value)

**Description:** 
The SCTP decoder extracts the `CRC32c` checksum but never mathematically validates it. While DTLS provides the primary cryptographic integrity guarantee for WebRTC, RFC 8261 still formally requires SCTP checksum validation to drop corrupted packets early.

**Remediation:** 
Compute the CRC32c checksum of incoming packets (with the checksum field set to 0) and compare it against the parsed value. Drop packets that fail validation.

---

### 2.9 [MEDIUM] SCTP SYN-Flood Equivalent (Missing State Cookie Authentication)
**Location:** `src/datachannel/chunks.clj` (`process-chunk :init` & `:cookie-echo`)
**CWE:** CWE-345 (Insufficient Verification of Data Authenticity)

**Description:** 
SCTP relies on a cryptographically signed State Cookie during the 4-way handshake to prevent state exhaustion attacks. The implementation currently allocates full state upon receiving `INIT`, generates 32 random bytes as a dummy cookie, and unconditionally transitions to `:established` upon receiving *any* `COOKIE-ECHO`.

**Impact:** 
An attacker can flood the server with `INIT` or spoofed `COOKIE-ECHO` packets, effortlessly forcing the server to allocate memory for ghost associations and bypassing setup checks.

**Remediation:** 
Serialize the Transmission Control Block (TCB) state and a timestamp, sign it with a local secret HMAC-SHA256 key, and send it in the `INIT-ACK`. Remain stateless until a valid `COOKIE-ECHO` is received and cryptographically verified.

---

## 4. Conclusion & Recommended Action Plan

The `alpeware-datachannel-clj` codebase is structurally excellent, leveraging functional programming to elegantly avoid classic concurrency state mutations. However, the outer-boundary integration and protocol handshake phases require immediate hardening.

**Immediate Priority Action Items:**
1. **Harden the API Loop:** Relocate the `try/catch` inside the `while @running` loop in `api.clj`. Add bounds checking to `dcep.clj` to prevent `BufferUnderflowException` crashes.
2. **Lock Down Network Boundaries:** Delete the `:else` raw SCTP block in `handle-receive`.
3. **Implement Verification Tags & Fingerprints:** Update `core/handle-sctp-packet` to assert Verification Tags, and strictly enforce DTLS fingerprint validation.
4. **Enforce STUN Authentication:** Add HMAC-SHA1 validation to all incoming STUN requests prior to emitting responses.
5. **Implement Resource Quotas:** Add a TTL-based eviction mechanism to the `routing-table` atom in `listen!` and apply maximum byte limits to the SCTP `:recv-queue`.
