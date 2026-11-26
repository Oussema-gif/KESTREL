#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

./mvnw -B -pl desktop-client -am verify
java -cp 'desktop-client/target/classes:desktop-client/target/test-classes' \
    com.kestrel.desktop.TelemetryFreshnessSmoke
