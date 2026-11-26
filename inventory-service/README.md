# Kestrel Inventory SOAP

Inventory listens on `127.0.0.1:8082` and implements the [Phase 1 SOAP contract](../contracts/soap/inventory.wsdl). The endpoint is `/services/inventory`. SOAP uses the shared Maintenance RS256 public key and the `inventory_app` PostgreSQL role.

## Run from the repository root

Keep the existing `.env` and database volume. Your Fedora PostgreSQL host port is `55432`. Inventory reads `INVENTORY_DB_PASSWORD` from that file and defaults to `.kestrel/keys/public.pem`. A separate public key path can be supplied with `KESTREL_JWT_PUBLIC_KEY`. Phase 2 Maintenance is needed to issue tokens for manual calls. Keep it running in its own terminal.

### Fedora / Bash

```bash
env -u POSTGRES_PORT docker compose up -d --wait postgres &&
./mvnw -B -pl inventory-service package &&
java -jar inventory-service/target/inventory-service-0.1.0-SNAPSHOT.jar
```

### Windows / PowerShell

```powershell
docker compose up -d --wait postgres
.\mvnw.cmd -B -pl inventory-service package
java -jar inventory-service\target\inventory-service-0.1.0-SNAPSHOT.jar
```

Use the Phase 2 key generator once if the key files do not exist. Do not regenerate the signing pair for each service. Inventory only needs the public key.

## Verify the published contract and call Inventory

1. Fetch [the live WSDL](http://127.0.0.1:8082/services/inventory?wsdl) while Inventory is running.
2. In Maintenance [Swagger UI](http://127.0.0.1:8081/swagger-ui/index.html), call `POST /auth/login` with an existing demo account. Copy the `accessToken`. In SoapUI, import the WSDL, open `checkStock`, and add the HTTP header `Authorization: Bearer <access token>`. The content type is `text/xml; charset=UTF-8`.
3. Enter `00000000-0000-4000-8000-000000000101` as the part ID and `1` as the requested quantity. On the initial demo stock, expect `availableQuantity=12` and `available=true`. Removing the header produces a SOAP fault with `UNAUTHENTICATED`.

### Fedora curl example

```bash
read -rsp 'Maintenance access token: ' kestrel_access_token
printf '\n'
printf 'Authorization: Bearer %s\n' "$kestrel_access_token" |
    curl -sS --header @- --header 'Content-Type: text/xml; charset=UTF-8' \
        --header 'SOAPAction: "urn:kestrel:inventory:v1/checkStock"' \
        --data-binary '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:inv="urn:kestrel:inventory:v1"><soap:Body><inv:checkStockRequest><inv:partId>00000000-0000-4000-8000-000000000101</inv:partId><inv:requestedQuantity>1</inv:requestedQuantity></inv:checkStockRequest></soap:Body></soap:Envelope>' \
        http://127.0.0.1:8082/services/inventory
unset kestrel_access_token
```

### Demo inventory

| Part | UUID | Initial available quantity |
| --- | --- | --- |
| Motor bearing | `00000000-0000-4000-8000-000000000101` | 12 |
| Hydraulic filter | `00000000-0000-4000-8000-000000000102` | 2 |
| Temperature sensor | `00000000-0000-4000-8000-000000000103` | 0 |

Use an operator or admin token to reserve the motor bearing. Retry with the same request UUID and matching fields to see a stable reservation UUID. Release it twice to see the original release timestamp. Try the temperature sensor to exercise the `INSUFFICIENT_STOCK` reserve fault.

## Test commands

| Check | Fedora | PowerShell |
| --- | --- | --- |
| Build and validate contracts | `./mvnw -B verify` | `.\mvnw.cmd -B verify` |
| Live SOAP integration tests | `./scripts/inventory-smoke-test.sh` | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\inventory-smoke-test.ps1` |

The integration tests normally start a separate PostgreSQL 16 Testcontainers database. They can also use a disposable database through `KESTREL_INVENTORY_TEST_DB_URL`, `KESTREL_INVENTORY_TEST_DB_USER` and `KESTREL_INVENTORY_TEST_DB_PASSWORD`. In that case, the test role must own the pre-created `inventory` schema. The tests write data. A regular build skips the opt-in inventory integration class.

## Reservation behavior

- Reserve locks the request UUID for the transaction, then locks the part row before checking and decrementing stock. The reservation insert and stock movement commit with the decrement.
- A replay with the same fields returns the same reservation UUID and current status. Conflicting fields return `REQUEST_CONFLICT`. Release locks the reservation row, restores stock once, and keeps the first release timestamp.
- A shortage produces a typed `INSUFFICIENT_STOCK` fault when reserving. A stock check reports `available=false` as an ordinary response. Purchase orders start in `CREATED` without increasing available stock.
- `workOrderId` is an external UUID. Inventory uses no connection to the Maintenance schema. The REST association is recorded through Maintenance after the caller confirms the SOAP result.
