$ErrorActionPreference = 'Stop'
$previousToken = $env:KESTREL_SMOKE_TOKEN
$previousEncoding = $OutputEncoding
$OutputEncoding = [Text.UTF8Encoding]::new($false)
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $rest = 'http://127.0.0.1:8081/api/v1'
    $soap = 'http://127.0.0.1:8082/services/inventory'
    $grpc = '127.0.0.1:9090'
    $snapshot = 'kestrel.telemetry.TelemetryService/GetMachineSnapshot'
    $machine = '{"machineCode":"MACHINE-001"}'
    $grpcArgs = @('-plaintext', '-max-time', '5', '-import-path', 'contracts/grpc', '-proto', 'telemetry.proto', '-d', '@')
    $xml = @'
<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/' xmlns:i='urn:kestrel:inventory:v1'><s:Body><i:checkStockRequest><i:partId>00000000-0000-4000-8000-000000000101</i:partId><i:requestedQuantity>1</i:requestedQuantity></i:checkStockRequest></s:Body></s:Envelope>
'@
    $soapArgs = @('--header', 'Content-Type: text/xml; charset=UTF-8',
        '--header', 'SOAPAction: urn:kestrel:inventory:v1/checkStock', '--data-binary', $xml.Trim(), $soap)

    $status = & curl.exe -sS --max-time 5 --output NUL --write-out '%{http_code}' "$rest/auth/me"
    if ($LASTEXITCODE -ne 0 -or $status -ne '401') { throw 'Expected 401 from Maintenance.' }
    $soapFault = & curl.exe -sS --max-time 5 @soapArgs
    if ($LASTEXITCODE -ne 0 -or "$soapFault" -notmatch 'UNAUTHENTICATED') { throw 'Expected an UNAUTHENTICATED SOAP fault.' }
    try {
        $ErrorActionPreference = 'Continue'
        $grpcFault = $machine | & grpcurl @grpcArgs $grpc $snapshot 2>&1
        $grpcExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = 'Stop'
    }
    if ($grpcExit -eq 0 -or "$grpcFault" -notmatch 'Code: Unauthenticated') { throw 'Expected Unauthenticated from Telemetry.' }
    Write-Host 'PASS. REST, SOAP and gRPC rejected calls without an access token.'

    $password = Read-Host 'Kestrel admin password' -AsSecureString
    $credential = [Net.NetworkCredential]::new('admin', $password)
    $login = @{ username = 'admin'; password = $credential.Password } | ConvertTo-Json -Compress
    $json = $login | & curl.exe -fsS --max-time 5 --header 'Content-Type: application/json' --data-binary '@-' "$rest/auth/login"
    if ($LASTEXITCODE -ne 0) { throw 'Maintenance login failed. Check the admin password.' }
    $token = ($json | ConvertFrom-Json).accessToken
    if (-not $token) { throw 'Maintenance did not return an access token.' }
    Remove-Variable password, credential, login, json

    $authorization = "Authorization: Bearer $token"
    $authorization | & curl.exe -fsS --max-time 5 --header '@-' --output NUL "$rest/auth/me"
    if ($LASTEXITCODE -ne 0) { throw 'Authenticated Maintenance call failed.' }
    $soapReply = $authorization | & curl.exe -fsS --max-time 5 --header '@-' @soapArgs
    if ($LASTEXITCODE -ne 0 -or "$soapReply" -notmatch 'checkStockResponse') { throw 'Authenticated Inventory call failed.' }

    $env:KESTREL_SMOKE_TOKEN = $token
    $machine | & grpcurl @grpcArgs -expand-headers -H 'authorization: Bearer ${KESTREL_SMOKE_TOKEN}' $grpc $snapshot | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Authenticated Telemetry call failed.' }
    Write-Host 'PASS. All three services accepted the same Maintenance access token.'
} finally {
    $env:KESTREL_SMOKE_TOKEN = $previousToken
    $OutputEncoding = $previousEncoding
    Pop-Location
}
