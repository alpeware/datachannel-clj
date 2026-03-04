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

## WebRTC Implementation Details

- This is a pure Clojure implementation of WebRTC Data Channels.
- Protocol stack: **SCTP over DTLS over UDP**.
- It uses Java NIO (`DatagramChannel`, `Selector`) for non-blocking I/O.
- DTLS is handled via `javax.net.ssl.SSLEngine`.
- SCTP implementation is custom and handles packet parsing, encoding, and CRC32C checksums.

### Reference Implementation
We use `dev.onvoid.webrtc/webrtc-java` as the reference implementation for compliance testing. Integration tests in `test/datachannel/webrtc_integration_test.clj` and `test/datachannel/stun_webrtc_integration_test.clj` verify interoperability with this library.

## Project Structure
- `src/datachannel/`: Source code.
- `test/datachannel/`: Test suite.
- `deps.edn`: Project dependencies and aliases.
