#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
export KESTREL_RUN_INVENTORY_DB_TESTS=true
printf '%s\n' 'Testing Inventory SOAP against an isolated PostgreSQL database...'
./mvnw -B -pl inventory-service test
