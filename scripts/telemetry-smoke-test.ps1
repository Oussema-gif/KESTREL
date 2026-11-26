$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    Write-Host 'Testing Telemetry over local gRPC...'
    & .\mvnw.cmd -B -pl telemetry-service test
    if ($LASTEXITCODE -ne 0) { throw "Telemetry smoke test failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
}
