# Kestrel Telemetry gRPC

Telemetry listens on `127.0.0.1:9090`. It produces one simulated reading per configured machine per second by default. The service has no database dependency.

| Source | RPC | Purpose |
| --- | --- | --- |
| `contracts/grpc/telemetry.proto` | `GetMachineSnapshot` | Return the latest reading and machine status |
| `contracts/grpc/telemetry.proto` | `StreamMachineTelemetry` | Stream new readings until the client cancels, its token expires, or the service shuts down |
| `contracts/grpc/simulation_control.proto` | `SetSimulationScenario` | Change the selected machine's simulation profile for the current process |

All calls take `authorization: Bearer <access token>` metadata. The four Kestrel roles can read telemetry. `ADMIN` and `MAINTENANCE_OPERATOR` can change scenarios. Telemetry verifies Maintenance-issued RS256 tokens with its public key. This academic demo uses plaintext gRPC on loopback.

## Run on Linux

From the repository root, run:

```bash
./mvnw -B -pl telemetry-service package &&
java -jar telemetry-service/target/telemetry-service-0.1.0-SNAPSHOT-exec.jar
```

## Run on Windows (PowerShell)

From the repository root, run:

```powershell
.\mvnw.cmd -B -pl telemetry-service package
if ($LASTEXITCODE -eq 0) {
    java -jar telemetry-service\target\telemetry-service-0.1.0-SNAPSHOT-exec.jar
}
```

Use JDK 21. The runnable artifact ends in `-exec.jar`. Maven also builds a regular JAR containing the generated stubs for other modules. Reuse the public key from Phase 2. Use the key-generation script if this is a fresh checkout. `KESTREL_JWT_PUBLIC_KEY` can point to a standalone copy of `public.pem`.

## Demo configuration

The default machine codes are `MACHINE-001`, `MACHINE-002`, and `MACHINE-003`. Configure `TELEMETRY_MACHINE_CODES` as a comma-separated list if the demo uses different codes. Register matching codes in Maintenance when combining REST and gRPC. `TELEMETRY_INTERVAL` defaults to `1s`. `TELEMETRY_INITIAL_SCENARIO` defaults to `NORMAL`. The scenario RPC changes a single machine without a restart. Restarting restores the configured initial mode.

| Scenario | Behavior |
| --- | --- |
| `NORMAL` | Generates baseline measurements |
| `HIGH_TEMPERATURE` | Temperature above its critical threshold |
| `HIGH_VIBRATION` | Vibration above its critical threshold |
| `PRESSURE_SPIKE` | Pressure above its critical threshold |
| `ENERGY_SPIKE` | Power above its critical threshold |
| `OFFLINE` | Pauses new samples. Snapshots retain the last timestamp and report `OFFLINE` when a prior sample exists. |

| Metric | Demo warning / critical | Optional `.env` overrides |
| --- | --- | --- |
| Temperature | 75 / 85 °C | `TELEMETRY_TEMPERATURE_WARNING`, `TELEMETRY_TEMPERATURE_CRITICAL` |
| Vibration | 4 / 7 mm/s | `TELEMETRY_VIBRATION_WARNING`, `TELEMETRY_VIBRATION_CRITICAL` |
| Pressure | 6 / 7 bar | `TELEMETRY_PRESSURE_WARNING`, `TELEMETRY_PRESSURE_CRITICAL` |
| Power | 12 / 15 kW | `TELEMETRY_ENERGY_WARNING`, `TELEMETRY_ENERGY_CRITICAL` |

Thresholds are demo values only. A reading above any critical threshold is `CRITICAL`. If no critical threshold is exceeded, a reading above any warning threshold is `WARNING`. Otherwise it is `RUNNING`. An offline machine with no previous sample returns `FAILED_PRECONDITION`. A stream remains open while offline and resumes on the next simulation tick after the machine returns online. A slow client can miss samples. The simulator sends the latest available reading and does not backfill history.

## Verify

| Check | Fedora / Bash | Windows PowerShell |
| --- | --- | --- |
| Telemetry integration tests | `./scripts/telemetry-smoke-test.sh` | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\telemetry-smoke-test.ps1` |
| Live security on all three services | `./scripts/security-smoke-test.sh` | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\security-smoke-test.ps1` |

The telemetry tests start temporary loopback gRPC servers. They require no running services or Docker. The security smoke test expects all three executable services running on their default ports with shared signing keys, `MACHINE-001` online, and the demo bearing present. Use the Maintenance `admin` password when prompted. Install `grpcurl` on PATH. The Fedora security script also uses `curl` and `python3`. The Windows script uses `curl.exe`.

### Fedora grpcurl demonstration

Copy a Maintenance `accessToken` from Swagger, then run from the repository root. Use an admin or maintenance operator token for the scenario change.

```bash
read -rsp 'Maintenance access token: ' KESTREL_DEMO_TOKEN
printf '\n'
export KESTREL_DEMO_TOKEN

grpcurl -plaintext -expand-headers -H 'authorization: Bearer ${KESTREL_DEMO_TOKEN}' \
  -import-path contracts/grpc -proto simulation_control.proto \
  -d '{"machineCode":"MACHINE-001","scenario":"HIGH_TEMPERATURE"}' \
  127.0.0.1:9090 kestrel.telemetry.SimulationControlService/SetSimulationScenario

grpcurl -plaintext -expand-headers -H 'authorization: Bearer ${KESTREL_DEMO_TOKEN}' \
  -import-path contracts/grpc -proto telemetry.proto \
  -d '{"machineCode":"MACHINE-001"}' \
  127.0.0.1:9090 kestrel.telemetry.TelemetryService/GetMachineSnapshot

grpcurl -plaintext -expand-headers -H 'authorization: Bearer ${KESTREL_DEMO_TOKEN}' \
  -import-path contracts/grpc -proto telemetry.proto \
  -d '{"machineCode":"MACHINE-001"}' \
  127.0.0.1:9090 kestrel.telemetry.TelemetryService/StreamMachineTelemetry

unset KESTREL_DEMO_TOKEN
```

Expect `CRITICAL` in the snapshot and live readings above 85 °C at the default thresholds. Stop the stream with **Ctrl+C**. The service remains available for new streams. Run `unset KESTREL_DEMO_TOKEN` once you finish using that shell. To return the demo to baseline, send `NORMAL` through the scenario RPC or restart with the default startup configuration.

### Windows grpcurl demonstration

```powershell
$secureToken = Read-Host 'Maintenance access token' -AsSecureString
$env:KESTREL_DEMO_TOKEN = [Net.NetworkCredential]::new('kestrel', $secureToken).Password
try {
    '{"machineCode":"MACHINE-001","scenario":"HIGH_TEMPERATURE"}' |
        grpcurl -plaintext -expand-headers -H 'authorization: Bearer ${KESTREL_DEMO_TOKEN}' -import-path contracts/grpc -proto simulation_control.proto -d '@' 127.0.0.1:9090 kestrel.telemetry.SimulationControlService/SetSimulationScenario
    '{"machineCode":"MACHINE-001"}' |
        grpcurl -plaintext -expand-headers -H 'authorization: Bearer ${KESTREL_DEMO_TOKEN}' -import-path contracts/grpc -proto telemetry.proto -d '@' 127.0.0.1:9090 kestrel.telemetry.TelemetryService/GetMachineSnapshot
    '{"machineCode":"MACHINE-001"}' |
        grpcurl -plaintext -expand-headers -H 'authorization: Bearer ${KESTREL_DEMO_TOKEN}' -import-path contracts/grpc -proto telemetry.proto -d '@' 127.0.0.1:9090 kestrel.telemetry.TelemetryService/StreamMachineTelemetry
} finally {
    Remove-Item Env:KESTREL_DEMO_TOKEN -ErrorAction SilentlyContinue
}
```
