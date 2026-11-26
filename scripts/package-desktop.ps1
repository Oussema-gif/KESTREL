$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'package-desktop.py'
if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 -B $scriptPath @args
} else {
    & python -B $scriptPath @args
}
exit $LASTEXITCODE
