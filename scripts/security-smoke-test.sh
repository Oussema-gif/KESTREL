#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
rest=http://127.0.0.1:8081/api/v1
soap=http://127.0.0.1:8082/services/inventory
grpc=127.0.0.1:9090
snapshot=kestrel.telemetry.TelemetryService/GetMachineSnapshot
machine='{"machineCode":"MACHINE-001"}'
xml='<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" xmlns:i="urn:kestrel:inventory:v1"><s:Body><i:checkStockRequest><i:partId>00000000-0000-4000-8000-000000000101</i:partId><i:requestedQuantity>1</i:requestedQuantity></i:checkStockRequest></s:Body></s:Envelope>'
soap_args=(--header 'Content-Type: text/xml; charset=UTF-8'
           --header 'SOAPAction: "urn:kestrel:inventory:v1/checkStock"'
           --data-binary "$xml" "$soap")
grpc_args=(-plaintext -max-time 5 -import-path contracts/grpc -proto telemetry.proto)

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

[[ "$(curl -sS --max-time 5 --output /dev/null --write-out '%{http_code}' "$rest/auth/me")" == 401 ]] ||
    fail 'Maintenance did not reject the unauthenticated request with 401.'
soap_fault=$(curl -sS --max-time 5 "${soap_args[@]}")
[[ "$soap_fault" == *UNAUTHENTICATED* ]] || fail 'Inventory did not return an UNAUTHENTICATED SOAP fault.'
if grpc_fault=$(grpcurl "${grpc_args[@]}" -d "$machine" "$grpc" "$snapshot" 2>&1); then
    fail 'Telemetry accepted the unauthenticated request.'
fi
[[ "$grpc_fault" == *'Code: Unauthenticated'* ]] || fail 'Telemetry did not return Unauthenticated.'
printf '%s\n' 'PASS. REST, SOAP and gRPC rejected calls without an access token.'

read -rsp 'Kestrel admin password: ' password
printf '\n'
token=$(printf '%s' "$password" |
    python3 -c 'import json, sys; print(json.dumps({"username":"admin","password":sys.stdin.read()}))' |
    curl -fsS --max-time 5 --header 'Content-Type: application/json' --data-binary @- "$rest/auth/login" |
    python3 -c 'import json, sys; print(json.load(sys.stdin)["accessToken"])') ||
    fail 'Maintenance login failed. Check the admin password.'
unset password

printf 'Authorization: Bearer %s\n' "$token" |
    curl -fsS --max-time 5 --header @- --output /dev/null "$rest/auth/me"
soap_reply=$(printf 'Authorization: Bearer %s\n' "$token" |
    curl -fsS --max-time 5 --header @- "${soap_args[@]}")
[[ "$soap_reply" == *checkStockResponse* ]] || fail 'Inventory did not return the stock-check response.'
KESTREL_SMOKE_TOKEN="$token" grpcurl "${grpc_args[@]}" -expand-headers \
    -H 'authorization: Bearer ${KESTREL_SMOKE_TOKEN}' -d "$machine" "$grpc" "$snapshot" >/dev/null
unset token
printf '%s\n' 'PASS. All three services accepted the same Maintenance access token.'
