#!/usr/bin/env bash
export FUZZ_ITERS=10000
clojure -M:test -m datachannel.test-runner
