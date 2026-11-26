$ErrorActionPreference = 'Stop'
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
    $keys = if ($env:KESTREL_KEYS_DIR) { $env:KESTREL_KEYS_DIR } else { '.kestrel/keys' }
    & $java scripts/GenerateKeys.java $keys
    if ($LASTEXITCODE -ne 0) { throw "Key generation failed (exit $LASTEXITCODE)." }
} finally {
    Pop-Location
}
