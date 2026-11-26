#!/usr/bin/env bash
set -euo pipefail

check_role() {
    local role="$1" password="$2" own_schema="$3" other_schema="$4"

    PGPASSWORD="$password" psql -X --no-password --quiet \
        -h 127.0.0.1 -U "$role" -d "$POSTGRES_DB" \
        -v ON_ERROR_STOP=1 -v own_schema="$own_schema" -v other_schema="$other_schema" <<'SQL'
BEGIN;

SELECT (
    current_schema() = :'own_schema'
    AND NOT has_schema_privilege(:'other_schema', 'USAGE')
    AND NOT has_schema_privilege(:'other_schema', 'CREATE')
    AND NOT has_schema_privilege('public', 'USAGE')
    AND NOT has_schema_privilege('public', 'CREATE')
    AND NOT has_database_privilege(current_database(), 'CREATE')
) AS privileges_ok
\gset
\if :privileges_ok
\else
    \warn Unexpected role privileges or default schema.
    SELECT 1 / 0;
\endif

CREATE TABLE :"own_schema".phase0_isolation_probe (id integer PRIMARY KEY);
INSERT INTO :"own_schema".phase0_isolation_probe VALUES (1);
SELECT EXISTS (SELECT FROM :"own_schema".phase0_isolation_probe WHERE id = 1) AS own_read_ok
\gset
\if :own_read_ok
\else
    \warn Failed to read the row from the owned schema.
    SELECT 1 / 0;
\endif

SELECT set_config('kestrel.other_schema', :'other_schema', true) AS configured
\gset
DO $probe$
BEGIN
    BEGIN
        EXECUTE format('CREATE TABLE %I.phase0_isolation_probe (id integer)',
                       current_setting('kestrel.other_schema'));
        RAISE EXCEPTION 'Cross-schema CREATE unexpectedly succeeded';
    EXCEPTION WHEN insufficient_privilege THEN
        NULL;
    END;
END
$probe$;
ROLLBACK;
SQL

    printf 'PASS %s owns %s and is denied access to %s\n' "$role" "$own_schema" "$other_schema"
}

check_role maintenance_app "$MAINTENANCE_DB_PASSWORD" maintenance inventory
check_role inventory_app "$INVENTORY_DB_PASSWORD" inventory maintenance
