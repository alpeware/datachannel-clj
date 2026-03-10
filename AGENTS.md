# Agent Instructions for datachannel-clj

## Environment Setup

### Clojure CLI
If the Clojure CLI is not installed, you can install it locally using the provided `linux-install.sh` script:

```bash
./linux-install.sh --prefix $(pwd)/.clojure-env
export PATH=$(pwd)/.clojure-env/bin:$PATH
```

### JVM Options
This library uses internal `sun.*` Java classes for certificate generation. Ensure the following flags are passed to the JVM:
- `--add-exports=java.base/sun.security.tools.keytool=ALL-UNNAMED`
- `--add-exports=java.base/sun.security.x509=ALL-UNNAMED`

These are already included in the `:test` alias in `deps.edn`.

## Running Tests

To run the full test suite:

```bash
clojure -M:test -m datachannel.test-runner
```

Individual test namespaces can be run as follows:

```bash
clojure -M:test -e "(require 'datachannel.sctp-test) (clojure.test/run-tests 'datachannel.sctp-test)"
```

Agents MUST run `clojure -M:lint` and fix all linting errors before they are allowed to open a Pull Request.

## WebRTC Implementation Details

- This is a pure Clojure implementation of WebRTC Data Channels.
- Protocol stack: **SCTP over DTLS over UDP**.
- It uses Java NIO (`DatagramChannel`, `Selector`) for non-blocking I/O.
- DTLS is handled via `javax.net.ssl.SSLEngine`.
- SCTP implementation is custom and handles packet parsing, encoding, and CRC32C checksums.

### Reference Implementation
We use `dev.onvoid.webrtc/webrtc-java` as the reference implementation for compliance testing. Integration tests in `test/datachannel/webrtc_integration_test.clj` and `test/datachannel/stun_webrtc_integration_test.clj` verify interoperability with this library.

## Reference Implementation (webrtc-java) Guide

### Data Channels (`RTCDataChannelInit`)
- **Reliability and Ordering**:
    - `ordered`: Default `true`.
    - **Reliable** (default): `maxPacketLifeTime = -1`, `maxRetransmits = -1`.
    - **Time-limited**: Set `maxPacketLifeTime` (ms), `maxRetransmits = -1`.
    - **Count-limited**: Set `maxPacketLifeTime = -1`, `maxRetransmits` (count).
- **Other options**:
    - `negotiated`: If `true`, the channel is out-of-band negotiated. Default `false`.
    - `id`: Manual ID (0-65535). Default `-1` (auto-assigned).
    - `protocol`: Sub-protocol name string.
    - `priority`: `RTCPriorityType` (e.g., `VERY_LOW`, `LOW`, `MEDIUM`, `HIGH`).

### Port Allocator (`PortAllocatorConfig`)
- `minPort` / `maxPort`: Restrict ephemeral port range for candidates. `0` means unspecified.
- **Common Flags**:
    - `PORTALLOCATOR_DISABLE_UDP`, `PORTALLOCATOR_DISABLE_STUN`, `PORTALLOCATOR_DISABLE_RELAY`, `PORTALLOCATOR_DISABLE_TCP`.
    - `PORTALLOCATOR_ENABLE_IPV6`, `PORTALLOCATOR_ENABLE_SHARED_SOCKET`.
    - `PORTALLOCATOR_DISABLE_ADAPTER_ENUMERATION`, `PORTALLOCATOR_DISABLE_COSTLY_NETWORKS`.

### RTC Stats (`RTCStatsType`)
Stats are collected via `RTCPeerConnection.getStats()`.
- **Common Types**: `INBOUND_RTP`, `OUTBOUND_RTP`, `DATA_CHANNEL`, `TRANSPORT`, `CANDIDATE_PAIR`, `LOCAL_CANDIDATE`, `REMOTE_CANDIDATE`.
- **Useful Attributes**:
    - `INBOUND_RTP`: `packetsReceived`, `bytesReceived`, `packetsLost`, `jitter`.
    - `OUTBOUND_RTP`: `packetsSent`, `bytesSent`.
    - `CANDIDATE_PAIR`: `nominated`, `state`, `currentRoundTripTime`, `bytesSent`, `bytesReceived`.

### Logging
Managed via `dev.onvoid.webrtc.logging.Logging`.
- **Severity Levels**: `VERBOSE`, `INFO`, `WARNING`, `ERROR`, `NONE`.
- **Configuration**: `logToDebug(severity)`, `logThreads(bool)`, `logTimestamps(bool)`.
- **Custom Sinks**: Implement `LogSink` interface and register via `addLogSink(severity, sink)`.

## Project Structure
- `src/datachannel/`: Source code.
- `test/datachannel/`: Test suite.
- `deps.edn`: Project dependencies and aliases.
