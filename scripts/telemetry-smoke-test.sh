#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
printf '%s\n' 'Testing Telemetry over local gRPC...'
./mvnw -B -pl telemetry-service test
