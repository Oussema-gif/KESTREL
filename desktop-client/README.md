# Kestrel Desktop · FORGE OPS

The JavaFX client connects to Maintenance REST, Inventory SOAP, and Telemetry gRPC. The client obtains a session from Maintenance and uses its access token across the three services. The Orchestration view shows successive calls and lets an authorized operator resume a saved step.

## Start

Use Java 21 and run the commands from the repository root. Configure the local `.env` and demo accounts according to the root README. Phase 9 launchers also need Python 3.9 or newer on PATH. For backend-only operation, use `backend` in place of `all` and launch the desktop in a separate terminal.

| Action | Fedora | Windows PowerShell |
|---|---|---|
| Start all services and client | `./scripts/run-all.sh all` | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-all.ps1 all` |
| Launch desktop against running services | `./scripts/run-all.sh desktop` | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-all.ps1 desktop` |
| Run verification suite | `./scripts/phase9-smoke-test.sh` | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\phase9-smoke-test.ps1` |

The client expects REST on `127.0.0.1:8081`, SOAP on `127.0.0.1:8082`, and gRPC on `127.0.0.1:9090`. The server logs from the combined launcher are under `.kestrel/logs/`. See [the rehearsal guide](../docs/kestrel-demo.md) for a four-terminal setup that lets you restart services independently.

The workflow journal under the user's home directory stores pending orchestration identifiers. Continue a pending step from Orchestration before beginning another run. Keep the journal when a reservation has an uncertain outcome or is still waiting to be linked.

## Service documentation

- [Maintenance REST](../maintenance-service/README.md)
- [Inventory SOAP](../inventory-service/README.md)
- [Telemetry gRPC](../telemetry-service/README.md)
- [Canonical contracts](../contracts/README.md)
