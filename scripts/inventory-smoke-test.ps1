$ErrorActionPreference = 'Stop'
$previous = $env:KESTREL_RUN_INVENTORY_DB_TESTS
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $env:KESTREL_RUN_INVENTORY_DB_TESTS = 'true'
    Write-Host 'Testing Inventory SOAP against an isolated PostgreSQL database...'
    & .\mvnw.cmd -B -pl inventory-service test
    if ($LASTEXITCODE -ne 0) { throw "Inventory smoke test failed (exit $LASTEXITCODE)." }
} finally {
    $env:KESTREL_RUN_INVENTORY_DB_TESTS = $previous
    Pop-Location
}
