#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
export KESTREL_RUN_DB_TESTS=true
printf '%s\n' 'Testing Maintenance REST against an isolated PostgreSQL database...'
./mvnw -B -pl maintenance-service test
