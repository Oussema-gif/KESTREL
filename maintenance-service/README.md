# Kestrel Maintenance REST

Maintenance listens on `127.0.0.1:8081` and serves the Phase 1 contract at `/api/v1`. Open [Swagger UI](http://127.0.0.1:8081/swagger-ui/index.html) after starting the service. It loads the checked-in OpenAPI file. Maven packages that file into the executable JAR.

## Run from the repository root

1. Keep the Phase 0 `.env` and PostgreSQL volume. On Fedora the previously verified host port was `55432`.
2. For demo accounts, open `.env` in an editor and add these four entries with different passwords you choose. Each password must be at least 12 characters and at most 72 UTF-8 bytes. The file also serves as a Java properties file for the application. Use plain alphanumeric passwords without quotes for these local examples.

   ```text
   KESTREL_DEMO_ADMIN_PASSWORD=
   KESTREL_DEMO_MAINTENANCE_PASSWORD=
   KESTREL_DEMO_TECHNICIAN_PASSWORD=
   KESTREL_DEMO_INVENTORY_PASSWORD=
   ```

   Leave all four unset to skip optional demo seeding. The demo usernames are `admin`, `maintenance`, `technician`, and `inventory`. Startup creates missing demo users and keeps existing hashes. Editing the seeds after an account first appears does not rotate its password. The `admin` profile is named Rahmouni Oussema.

### Fedora / Bash

```bash
./scripts/gen-keys.sh &&
env -u POSTGRES_PORT docker compose up -d --wait postgres &&
./mvnw -B -pl maintenance-service package &&
java -jar maintenance-service/target/maintenance-service-0.1.0-SNAPSHOT.jar
```

If your extractor dropped execute permissions, run `chmod +x scripts/gen-keys.sh scripts/maintenance-smoke-test.sh` first. The key script respects `JAVA_HOME`. If Maven selects an older JDK, reuse the Phase 0 `javac` detection before building.

### Windows / PowerShell

Set `JAVA_HOME` to your JDK 21 and put its `bin` directory on PATH. Start Docker Desktop in Linux containers mode. From the repository root, run:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\gen-keys.ps1
docker compose up -d --wait postgres
.\mvnw.cmd -B -pl maintenance-service package
java -jar maintenance-service\target\maintenance-service-0.1.0-SNAPSHOT.jar
```

If Create or Build fails, resolve that error before starting the JAR.

## Local verification

With Maintenance running, open Swagger UI. Execute `POST /auth/login` with the `admin` demo credentials. Copy only the `accessToken` value into the Authorize dialog, then execute `GET /auth/me`. Expect `200`, `role: ADMIN` and `fullName: Rahmouni Oussema`. `GET /machines?page=0&size=20` returns the pagination envelope. Empty `content` is normal before registering any machines. Visit `/api/v1/machines` in a private browser window without authorization to verify `401`.

| Check | Fedora | PowerShell |
| --- | --- | --- |
| Reactor and contract validation | `./mvnw -B verify` | `.\mvnw.cmd -B verify` |
| Real HTTP integration tests against a disposable PostgreSQL database | `./scripts/maintenance-smoke-test.sh` | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\maintenance-smoke-test.ps1` |

The integration tests normally start a separate `postgres:16` Testcontainers instance. They do not use the running Phase 0 database. Developers can override the test connection with `KESTREL_TEST_DB_URL`, `KESTREL_TEST_DB_USER`, and `KESTREL_TEST_DB_PASSWORD` for a **disposable database** with a maintenance schema owned by that test role. The test suite writes data there. Ordinary Maven builds skip this opt-in test class.

## Persistence and authentication

- Flyway applies the migration through `maintenance_app`. Spring JDBC maps rows into typed records and sends SQL inputs as parameters. The service writes only to the `maintenance` schema.
- The machine record is locked when deduplicating alerts. A unique partial index also enforces one unresolved alert per registered machine and metric. Maintenance status reflects the latest accepted alert observation. Live telemetry belongs to the later gRPC service.
- Work-order updates lock the order row before evaluating its state. A separate table remembers released reservation UUIDs so old replays cannot reattach them. The REST reservation endpoint records the authorized orchestrator's already-confirmed SOAP result. Live SOAP verification becomes possible when Inventory is implemented.
- Signing keys default to `.kestrel/keys`. Key generation preserves an existing pair. Files are ignored by Git. `private.pem` stays with Maintenance. Give only `public.pem` to other services. For custom key paths, set `KESTREL_KEYS_DIR` in the environment of both key generation and the application.
- Demo passwords are BCrypt hashes at cost 12. Access tokens are RS256 JWTs with 15-minute lifetimes. The database stores SHA-256 digests of randomly generated refresh tokens. A conditional update atomically consumes a valid refresh token before issuing its successor.
- The single-service demo uses in-memory IP rate limits and account lockout. Audit rows retain the outcomes of authentication, denied operations, work-order transitions, and reservation updates. The admin can page through them via `/audit-events`.
