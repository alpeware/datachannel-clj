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
- [ ] **SEC-01:** Implement negative integration test for DTLS fingerprint mismatch (MITM rejection).
