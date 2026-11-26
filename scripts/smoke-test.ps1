$ErrorActionPreference = 'Stop'

Push-Location (Join-Path $PSScriptRoot '..')
try {
    if (-not (Test-Path -LiteralPath '.env' -PathType Leaf)) {
        throw 'Copy .env.example to .env and set the three local passwords first.'
    }

    Write-Host 'Building the four Kestrel modules with Java 21...'
    & .\mvnw.cmd -B verify
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)." }

    Write-Host 'Starting PostgreSQL and waiting for TCP readiness...'
    & docker compose up -d --wait --wait-timeout 90 postgres
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL startup failed (exit $LASTEXITCODE)." }

    Write-Host 'Checking PostgreSQL role isolation...'
    & docker compose exec -T postgres bash /kestrel-check-db-isolation.sh
    if ($LASTEXITCODE -ne 0) { throw "Database isolation check failed (exit $LASTEXITCODE)." }

    Write-Host 'Phase 0 smoke test passed.'
} finally {
    Pop-Location
}
