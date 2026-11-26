#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
umask 077
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_bin="$JAVA_HOME/bin/java"
else
    java_bin=java
fi
"$java_bin" scripts/GenerateKeys.java "${KESTREL_KEYS_DIR:-.kestrel/keys}"
