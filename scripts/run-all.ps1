$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'kestrel_local.py'
if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 -B $scriptPath start @args
} else {
    & python -B $scriptPath start @args
}
exit $LASTEXITCODE
