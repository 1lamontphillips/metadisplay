#!/usr/bin/env bash
set -euo pipefail

source /workspace/android-setup/env.sh
transport_test_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
transport_test_classes="$(mktemp -d /tmp/transport-server-tests.XXXXXX)"
trap 'rm -rf -- "$transport_test_classes"' EXIT

javac --release 17 -d "$transport_test_classes" \
  "$transport_test_root/app/src/main/java/com/lamontphillips/metadisplay/transporttest/ClipHttpServer.java" \
  "$transport_test_root/host-tests/com/lamontphillips/metadisplay/transporttest/ClipHttpServerTest.java"
java -cp "$transport_test_classes" com.lamontphillips.metadisplay.transporttest.ClipHttpServerTest
