#!/usr/bin/env bash
set -euo pipefail
source /workspace/android-setup/env.sh
export GRADLE_USER_HOME=/workspace/android-setup/gradle-home
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
transport_project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$transport_project_root"
./gradlew --no-daemon --max-workers=2 --console=plain :app:assembleDebug :app:lintDebug
