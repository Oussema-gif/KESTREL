$ErrorActionPreference = 'Stop'
$previous = $env:KESTREL_RUN_DB_TESTS
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $env:KESTREL_RUN_DB_TESTS = 'true'
    Write-Host 'Testing Maintenance REST against an isolated PostgreSQL database...'
    & .\mvnw.cmd -B -pl maintenance-service test
    if ($LASTEXITCODE -ne 0) { throw "Maintenance smoke test failed (exit $LASTEXITCODE)." }
} finally {
    $env:KESTREL_RUN_DB_TESTS = $previous
    Pop-Location
}
