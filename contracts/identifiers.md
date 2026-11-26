# Canonical identifier conventions

| Value | Format | Used by |
| --- | --- | --- |
| REST resource ID | UUID string | Machines, alerts, work orders, technicians and reservation references |
| `machineId` | Machine UUID | REST routes, references and the alert and work-order DTOs |
| `machineCode` | Unique machine code such as `MACHINE-001` | gRPC telemetry, anomaly deduplication, and the alert and work-order DTOs |
| `workOrderId` in SOAP | Work-order UUID serialized as an XSD string | Inventory reservation's external reference to Maintenance |
| `reservationId` | Reservation UUID serialized as an XSD string in SOAP | SOAP reservation response and the corresponding REST reference |

- Maintenance owns the mapping from machine UUID to machine code. The code is stable and unique.
- gRPC uses the machine code in requests, snapshots and stream readings. At contract definition in Phase 1, use `machine_code` and preserve the field numbers in the architecture specification.
- REST alert and work-order DTOs carry both `machineId` and `machineCode`. If a caller supplies both values, Maintenance validates that they identify the same registered machine.
- Deduplicate equivalent active alerts by the canonical `machineCode` and `metricType` pair.
- REST path parameters naming a resource use its UUID. A machine's code is a business key.
- Cross-service references travel as contract values. Foreign keys stay within the owning schema.

Example identity fields on an alert or a work order

```json
{
  "machineId": "1bd5ea63-fabd-4436-be40-9e7d37b7a171",
  "machineCode": "MACHINE-001"
}
```
