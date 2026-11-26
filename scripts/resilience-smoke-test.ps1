$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    & .\mvnw.cmd -B -pl desktop-client -am verify
    if ($LASTEXITCODE -ne 0) { throw 'Desktop Maven verification failed.' }

    & java -cp 'desktop-client\target\classes;desktop-client\target\test-classes' `
        com.kestrel.desktop.TelemetryFreshnessSmoke
    if ($LASTEXITCODE -ne 0) { throw 'Telemetry freshness smoke test failed.' }
} finally {
    Pop-Location
}
