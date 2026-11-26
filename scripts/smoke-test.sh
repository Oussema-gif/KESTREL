#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
if [[ ! -f .env ]]; then
    printf '%s\n' 'Copy .env.example to .env and set the three local passwords first.' >&2
    exit 1
fi

printf '%s\n' 'Building the four Kestrel modules with Java 21...'
./mvnw -B verify

printf '%s\n' 'Starting PostgreSQL and waiting for TCP readiness...'
docker compose up -d --wait --wait-timeout 90 postgres

printf '%s\n' 'Checking PostgreSQL role isolation...'
docker compose exec -T postgres bash /kestrel-check-db-isolation.sh
printf '%s\n' 'Phase 0 smoke test passed.'
