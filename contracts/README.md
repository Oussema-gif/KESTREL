# Kestrel API contracts

| Protocol | Source | Development address |
| --- | --- | --- |
| REST | [Maintenance OpenAPI](api/maintenance.openapi.yaml) | `http://localhost:8081/api/v1` |
| SOAP 1.1 | [Inventory WSDL](soap/inventory.wsdl) and [Inventory XSD](soap/inventory.xsd) | `http://localhost:8082/services/inventory?wsdl` |
| gRPC | [Telemetry Protobuf](grpc/telemetry.proto) | `localhost:9090` |

`./mvnw -B verify` from the repository root validates OpenAPI, generates and compiles Jakarta SOAP types, and generates and compiles Protobuf messages and gRPC stubs. The corresponding PowerShell command is `.mvnw.cmd -B verify`. Use the existing platform smoke script to include PostgreSQL role isolation. The service addresses above apply when the corresponding service implementations are running.

## Shared conventions

- [Canonical identifiers](identifiers.md) define which values cross the service boundaries. The same UUID format applies to part, purchase-order and reservation request IDs. Generate a new `requestId` for each new reservation attempt and keep it across retries of that attempt.
- Machine codes are uppercase alphanumeric segments separated by single hyphens, up to 32 characters. REST alert and work-order writes require the UUID and machine code together. REST validates their relationship against the machine registry.
- REST date-times are RFC 3339 UTC with `Z`. SOAP `releasedAt` uses `xs:dateTime` in UTC with `Z`. gRPC `timestamp_epoch_ms` is an `int64` containing UTC milliseconds since the Unix epoch.
- Machine states are `RUNNING`, `WARNING`, `CRITICAL`, `OFFLINE`, and `MAINTENANCE`. REST models them as an enum. The gRPC snapshot carries the same values as a string to preserve the architecture's type at field 7.
- The telemetry units and alert metric names are fixed by the following mapping. Threshold values belong to the demo configuration.

| Metric type | gRPC field | Unit |
| --- | --- | --- |
| `TEMPERATURE` | `temperature_c` | °C |
| `VIBRATION` | `vibration_mm_s` | mm/s |
| `PRESSURE` | `pressure_bar` | bar |
| `ENERGY` | `energy_kw` | kW |

## Authentication and authorization

Maintenance issues RS256 JWT access tokens with `iss`, `sub`, `role`, `iat`, `exp`, and `jti`. The `sub` is the user UUID. Use `kestrel-maintenance` as the local issuer and `kestrel-services` as the `aud` claim. Each protected service checks the signature, issuer, audience, expiry, and role. Maintenance alone holds the signing key. The REST and SOAP transports use the HTTP `Authorization` header. gRPC uses the lowercase `authorization` metadata key. All three carry `Bearer <access token>`.

Login issues a token pair. Refresh authenticates with the opaque refresh token, rotates it, and invalidates the old token. Logout revokes the submitted refresh token and returns `204` on replay. The access token expires on its original schedule. Invalid credentials return a generic `401`, and rate-limited login or refresh returns `429` with `Retry-After` in seconds. JWTs, passwords, and refresh tokens are excluded from logs and error payloads.

| REST operation | ADMIN | MAINTENANCE_OPERATOR | TECHNICIAN | INVENTORY_OPERATOR |
| --- | --- | --- | --- | --- |
| Login, refresh, logout | Credentials/token | Credentials/token | Credentials/token | Credentials/token |
| Read current user | Yes | Yes | Yes | Yes |
| List/read machines and latest status | Yes | Yes | Yes | Yes |
| Register machine | Yes | — | — | — |
| List/read alerts | Yes | Yes | Yes | — |
| Create, acknowledge, or resolve alert | Yes | Yes | — | — |
| List/read work orders | Yes | Yes | Assigned only | Yes |
| Create work order | Yes | Yes | — | — |
| Change work-order status | Yes | Yes | Assigned orders, start/complete only | — |
| Assign technician or record reservation result | Yes | Yes | — | — |
| List technicians for assignment | Yes | Yes | — | — |
| List audit events | Yes | — | — | — |

| SOAP or gRPC operation | ADMIN | MAINTENANCE_OPERATOR | TECHNICIAN | INVENTORY_OPERATOR |
| --- | --- | --- | --- | --- |
| SOAP get spare part or check stock | Yes | Yes | Yes | Yes |
| SOAP reserve or release a part | Yes | Yes | — | Yes |
| SOAP create purchase order | Yes | — | — | Yes |
| gRPC snapshot or stream | Yes | Yes | Yes | Yes |
| gRPC set simulation scenario | Yes | Yes | — | — |

## REST behavior

- Every paginated response contains `content`, `page`, `size`, and `totalElements`. Page indexes start at 0. Sizes default to 20 and allow 1–100. Sort machines by code ascending, alerts by occurrence descending, work orders by creation descending, technicians by full name ascending, and audit events by timestamp descending. Break ties by UUID ascending.
- Return the same unresolved alert and HTTP `200` for a repeated `machineCode + metricType` pair while an alert is `ACTIVE` or `ACKNOWLEDGED`. A resolved alert allows creation of a new alert with `201`. Preserve the original alert's observations on replay.
- An alert-linked work order belongs to the alert's machine. An identical creation retry returns the existing order with `200`. A different payload for the same alert returns `409`. A manual order may omit the alert.
- Optional response fields are omitted when absent. Error responses always contain `code` and `message`. Include `violations` for field validation when available and `traceId` when a trace was assigned.
- Malformed IDs, mismatched machine UUID/code pairs, or invalid field values return `400`. Unknown resources return `404`. Forbidden access returns `403`. An invalid transition, conflicting update, or duplicate machine code returns `409`.

### Work-order transitions

| Current status | Next status | Condition |
| --- | --- | --- |
| `OPEN` | `CHECKING_INVENTORY` | The order requires a part. |
| `OPEN` | `READY_FOR_MAINTENANCE` | The order requires no part. |
| `CHECKING_INVENTORY` | `WAITING_FOR_PARTS` | A stock check or reserve call reported insufficient stock. |
| `CHECKING_INVENTORY` | `PENDING_RESERVATION` | The inventory outcome is still pending or uncertain. |
| `WAITING_FOR_PARTS`, `PENDING_RESERVATION` | `CHECKING_INVENTORY` | Retry the inventory workflow. |
| `CHECKING_INVENTORY`, `PENDING_RESERVATION` | `READY_FOR_MAINTENANCE` | Record a SOAP-confirmed `RESERVED` result through the reservation endpoint. |
| `READY_FOR_MAINTENANCE` | `CHECKING_INVENTORY` | A required reservation was released and must be reacquired. |
| `READY_FOR_MAINTENANCE` | `IN_PROGRESS` | A technician is assigned and any required reservation is confirmed. |
| `IN_PROGRESS` | `COMPLETED` | Work is complete. |
| Any nonterminal status | `CANCELLED` | An authorized operator cancels the order with no active reservation. |

`COMPLETED` and `CANCELLED` are terminal. Repeating a confirmed state update returns the current order. To cancel an order with an active reservation, release the reservation through SOAP, record its `RELEASED` result through REST, and then cancel. The reservation update accepts release for the current reservation ID only. Once recorded as released, a delayed `RESERVED` update for the same ID is rejected. A different reservation ID cannot replace an active reservation.

## SOAP behavior

- `checkStock` returns `available=false` with the observed quantity when stock is short. A concurrent reservation can still claim stock before `reservePart`. The reserve operation checks and deducts stock in one transaction and returns `INSUFFICIENT_STOCK` if it cannot reserve.
- Use the same UUID request ID when the result of `reservePart` is uncertain. For the same part, quantity, and work order, return the same reservation UUID and quantity and its current status. If it has since been released, the replay returns `RELEASED` without consuming stock again. Reusing the request ID with different input returns `REQUEST_CONFLICT`.
- A repeated release preserves the original release timestamp and restores no additional stock. A purchase-order response confirms creation. It does not change stock.
- SOAP faults carry the schema-defined `inventoryFault` detail with `code` and `message`. An insufficient-stock rejection may include `availableQuantity`. Map a malformed or nonpositive quantity to `INVALID_QUANTITY` when the invalid field is identifiable. Other malformed inputs use `INVALID_REQUEST`. Missing or invalid authorization uses `UNAUTHENTICATED` and a disallowed role uses `FORBIDDEN`.
- Attach only a SOAP-confirmed `RESERVED` result in REST. If the REST association fails after SOAP confirms the reservation, retain its ID and retry the REST update using that ID.

## gRPC behavior

- `GetMachineSnapshot` is unary. `StreamMachineTelemetry` is server-streaming and emits about one reading per second when the simulator produces data. Each sample carries the machine code, four metrics, and timestamp.
- Use finite numeric values. The snapshot timestamp refers to the last available reading. An offline machine with a cached sample can return that sample with status `OFFLINE`. The stream does not manufacture measurements during an outage.
- Use `INVALID_ARGUMENT` for an invalid machine code and `NOT_FOUND` for an unknown machine. A known machine with no initial snapshot returns `FAILED_PRECONDITION`. Use `UNAUTHENTICATED` for a missing or invalid token, `PERMISSION_DENIED` for a disallowed role, and `UNAVAILABLE` for a transient server outage.
- Preserve published Protobuf field numbers and wire types. The identifier name is `machine_code` at tag 1 in each of the three initial messages.

The [simulation control contract](grpc/simulation_control.proto) changes the active mode for one configured machine. The original data-service RPCs and message tags remain as published. Streams terminate with `UNAUTHENTICATED` when their access token expires.
