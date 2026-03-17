# Datachannel-clj Implementation Roadmap

## Phase 1: Core Transports (Sans-IO)
- [x] Implement ICE candidate gathering and connectivity checks.
- [x] Implement DTLS handshake and encryption wrapping/unwrapping.
- [x] Implement SCTP association, chunking, and reassembly.

## Phase 2: State Machine & Handlers
- [x] Implement `datachannel.core` Mealy machine reducer.
- [x] Implement `datachannel.handlers` for all network and app events.

## Phase 3: Security & Hardening
- [x] **LINT-01:** Tighten `.clj-kondo/config.edn` rules and resolve all codebase warnings. Merge this linter rules your config: `{:linters {:large-def {:level :warning :row-threshold 60}  :too-many-params {:level :warning :max-params 5}  :missing-docstring {:level :warning} :unsorted-required-namespaces {:level :warning}}}`
- [x] **SEC-01:** Implement negative integration test for DTLS fingerprint mismatch (MITM rejection).

## Phase 4: Security Audit Remediation
- [x] **[CRITICAL] Prevent Remote DoS in Network Loop:** Move the `try/catch` block inside the `while @running` loop in `api.clj` and add strict bounds checking to binary decoders like `dcep/decode-message` to prevent `BufferUnderflowException` thread crashes.
- [ ] **[CRITICAL] Enforce DTLS / Remove Raw SCTP Fallback:** Delete the `:else` raw SCTP fallback block in `core/handle-receive` to prevent DTLS encryption bypass.
- [ ] **[CRITICAL] Prevent Routing Table OOM:** Implement an LRU cache or TTL-based background reaper for `@routing-table` in `api.clj` and delay heavy RSA generation until after STUN verification.
- [ ] **[HIGH] Authenticate STUN Requests:** Update `stun/handle-packet` to validate the `MESSAGE-INTEGRITY` (HMAC-SHA1) attribute of inbound Binding Requests using the `:ice-pwd` before emitting responses.
- [ ] **[HIGH] Validate SCTP Verification Tags:** Update `core/handle-sctp-packet` to explicitly assert that the incoming `verification-tag` matches the expected `:local-ver-tag` (except for INIT chunks).
- [ ] **[HIGH] Enforce DTLS Fingerprint Verification:** Fix the TOCTOU bypass in `core/handle-receive` by explicitly requiring `:remote-fingerprint` to be non-nil and successfully verified before transitioning to `(:dtls-verified? true)`.
- [ ] **[MEDIUM] Bound SCTP Receive Queues:** Enforce a maximum byte limit on the `:recv-queue` vector in `chunks/process-chunk` to prevent memory exhaustion from infinite fragmented DATA chunks.
- [ ] **[MEDIUM] Validate SCTP CRC32c Checksums:** Compute and validate the CRC32c checksum of incoming SCTP packets in `sctp/decode-packet` to drop corrupted data.
- [ ] **[MEDIUM] Secure SCTP State Cookies:** Prevent SYN-flood state exhaustion by properly signing the TCB state with an HMAC-SHA256 secret in the `INIT-ACK` and remaining stateless until a valid `COOKIE-ECHO` is received.
