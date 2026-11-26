package com.kestrel.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "KESTREL_RUN_DB_TESTS", matches = "true")
class MaintenanceHttpTest {
    private static final String PASSWORD = "local-test-password-123";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper json;
    @Autowired
    JwtEncoder jwtEncoder;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        String override = System.getenv("KESTREL_TEST_DB_URL");
        if (override == null || override.isBlank()) {
            var postgres = new PostgreSQLContainer<>("postgres:16");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.flyway.create-schemas", () -> true);
        } else {
            registry.add("spring.datasource.url", () -> override);
            registry.add("spring.datasource.username", () -> System.getenv("KESTREL_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("KESTREL_TEST_DB_PASSWORD"));
        }
        Path keys = Files.createTempDirectory("kestrel-test-keys-");
        keys.toFile().deleteOnExit();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        writePem(keys.resolve("private.pem"), "PRIVATE KEY", pair.getPrivate().getEncoded());
        writePem(keys.resolve("public.pem"), "PUBLIC KEY", pair.getPublic().getEncoded());
        registry.add("kestrel.keys-dir", keys::toString);
        registry.add("kestrel.demo.admin", () -> PASSWORD);
        registry.add("kestrel.demo.maintenance-operator", () -> PASSWORD);
        registry.add("kestrel.demo.technician", () -> PASSWORD);
        registry.add("kestrel.demo.inventory-operator", () -> PASSWORD);
    }

    @Test
    void authenticatesRotatesRefreshAndRejectsInvalidTokens(CapturedOutput output) throws Exception {
        assertThat(call("GET", "/machines", null, null, 401).path("code").asText()).isEqualTo("UNAUTHENTICATED");
        JsonNode session = login("admin");
        String bearer = session.path("accessToken").asText();
        String refresh = session.path("refreshToken").asText();
        JsonNode profile = call("GET", "/auth/me", bearer, null, 200);
        assertThat(profile.path("fullName").asText()).isEqualTo("Rahmouni Oussema");

        int signatureStart = bearer.lastIndexOf('.') + 1;
        char replacement = bearer.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = bearer.substring(0, signatureStart) + replacement + bearer.substring(signatureStart + 1);
        call("GET", "/auth/me", tampered, null, 401);
        Instant now = Instant.now();
        var expiredClaims = JwtClaimsSet.builder().issuer(SecurityConfig.ISSUER)
                .audience(List.of(SecurityConfig.AUDIENCE)).subject(profile.path("id").asText())
                .id(UUID.randomUUID().toString()).issuedAt(now.minusSeconds(60)).expiresAt(now.minusSeconds(1))
                .claim("role", "ADMIN").build();
        String expired = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), expiredClaims)).getTokenValue();
        call("GET", "/auth/me", expired, null, 401);

        var rotate = request("POST", "/auth/refresh", null, Map.of("refreshToken", refresh));
        var first = http.sendAsync(rotate, HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(rotate, HttpResponse.BodyHandlers.ofString());
        var a = first.join();
        var b = second.join();
        assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(200, 401);
        JsonNode rotated = json.readTree(a.statusCode() == 200 ? a.body() : b.body());
        String replacementRefresh = rotated.path("refreshToken").asText();
        assertThat(replacementRefresh).isNotBlank().isNotEqualTo(refresh);
        var logout = Map.of("refreshToken", replacementRefresh);
        call("POST", "/auth/logout", null, logout, 204);
        call("POST", "/auth/logout", null, logout, 204);
        call("POST", "/auth/refresh", null, logout, 401);
        assertThat(output.getAll()).doesNotContain(PASSWORD, bearer, refresh, replacementRefresh, expired);
    }

    @Test
    void rateLimitsRepeatedInvalidLogins() throws Exception {
        var credentials = Map.of("username", "unknown-user", "password", "invalid");
        for (int i = 0; i < 5; i++) {
            assertThat(call("POST", "/auth/login", null, credentials, 401).path("code").asText())
                    .isEqualTo("INVALID_CREDENTIALS");
        }
        var response = http.send(request("POST", "/auth/login", null, credentials), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void deduplicatesAlertsAndEnforcesWorkOrderOwnership() throws Exception {
        String admin = login("admin").path("accessToken").asText();
        String operator = login("maintenance").path("accessToken").asText();
        String technician = login("technician").path("accessToken").asText();
        String inventory = login("inventory").path("accessToken").asText();
        String code = "MACHINE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        var machine = Map.of("machineCode", code, "name", "Press A", "location", "Plant 1", "model", "K-100");
        call("POST", "/machines", operator, machine, 403);
        String machineId = call("POST", "/machines", admin, machine, 201).path("id").asText();
        call("POST", "/machines", admin, machine, 409);
        call("GET", "/machines/" + machineId + "/status", admin, null, 404);
        call("GET", "/machines/1-1-1-1-1", admin, null, 400);
        call("GET", "/machines?size=101", admin, null, 400);

        var alert = Map.of("machineId", machineId, "machineCode", code, "metricType", "TEMPERATURE",
                "severity", "CRITICAL", "observedValue", 97, "thresholdValue", 90, "message", "Demo alert",
                "occurredAt", "2026-11-26T08:15:00Z");
        call("POST", "/alerts", inventory, alert, 403);
        var wrongCode = new java.util.HashMap<>(alert);
        wrongCode.put("machineCode", "MACHINE-WRONG");
        call("POST", "/alerts", operator, wrongCode, 400);
        var request = request("POST", "/alerts", operator, alert);
        var f1 = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var f2 = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var a = f1.join();
        var b = f2.join();
        assertThat(List.of(a.statusCode(), b.statusCode())).containsExactlyInAnyOrder(201, 200);
        String alertId = json.readTree(a.body()).path("id").asText();
        assertThat(json.readTree(b.body()).path("id").asText()).isEqualTo(alertId);
        call("PATCH", "/alerts/" + alertId + "/acknowledge", operator, null, 200);
        assertThat(call("POST", "/alerts", operator, alert, 200).path("id").asText()).isEqualTo(alertId);
        call("PATCH", "/alerts/" + alertId + "/resolve", operator, null, 200);
        call("POST", "/alerts", operator, alert, 201);
        assertThat(call("GET", "/machines/" + machineId + "/status", operator, null, 200).path("status").asText())
                .isEqualTo("CRITICAL");
        assertThat(call("GET", "/alerts?machineCode=" + code + "&size=1", operator, null, 200)
                .path("totalElements").asInt()).isEqualTo(2);

        var order = Map.of("machineId", machineId, "machineCode", code, "alertId", alertId, "title", "Check press",
                "description", "Investigate sensor", "priority", "HIGH");
        String id = call("POST", "/work-orders", operator, order, 201).path("id").asText();
        assertThat(call("POST", "/work-orders", operator, order, 200).path("id").asText()).isEqualTo(id);
        var different = new java.util.HashMap<>(order);
        different.put("title", "Different task");
        call("POST", "/work-orders", operator, different, 409);
        String path = "/work-orders/" + id;
        call("GET", path, technician, null, 403);
        call("GET", path, inventory, null, 200);
        status(path, "COMPLETED", operator, 409);
        status(path, "READY_FOR_MAINTENANCE", operator, 200);
        status(path, "IN_PROGRESS", operator, 409);
        String technicianId = call("GET", "/technicians?active=true", operator, null, 200)
                .path("content").get(0).path("id").asText();
        call("PATCH", path + "/assign", operator, Map.of("technicianId", technicianId), 200);
        status(path, "CANCELLED", technician, 403);
        status(path, "IN_PROGRESS", technician, 200);
        status(path, "COMPLETED", technician, 200);
        call("GET", "/audit-events", technician, null, 403);
        assertThat(call("GET", "/audit-events?size=100", admin, null, 200).path("content").toString())
                .contains("ACCESS_DENIED", "WORK_ORDER_COMPLETED");
    }

    @Test
    void recordsReservationAndRejectsStaleReplays() throws Exception {
        String admin = login("admin").path("accessToken").asText();
        String code = "PUMP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        String machine = call("POST", "/machines", admin,
                Map.of("machineCode", code, "name", "Pump", "location", "Plant", "model", "K-200"), 201).path("id").asText();
        var payload = Map.of("machineId", machine, "machineCode", code, "title", "Replace part",
                "description", "Maintenance order", "priority", "URGENT",
                "partRequirement", Map.of("partId", UUID.randomUUID().toString(), "quantity", 1));
        String id = call("POST", "/work-orders", admin, payload, 201).path("id").asText();
        String path = "/work-orders/" + id;
        status(path, "READY_FOR_MAINTENANCE", admin, 409);
        status(path, "CHECKING_INVENTORY", admin, 200);
        status(path, "WAITING_FOR_PARTS", admin, 200);
        status(path, "CHECKING_INVENTORY", admin, 200);
        status(path, "PENDING_RESERVATION", admin, 200);

        String firstId = UUID.randomUUID().toString();
        var reserve = Map.of("reservationId", firstId, "status", "RESERVED");
        JsonNode ready = call("PATCH", path + "/reservation", admin, reserve, 200);
        assertThat(ready.path("status").asText()).isEqualTo("READY_FOR_MAINTENANCE");
        call("PATCH", path + "/reservation", admin, reserve, 200);
        status(path, "CANCELLED", admin, 409);
        call("PATCH", path + "/reservation", admin, Map.of("reservationId", firstId, "status", "RELEASED"), 200);
        status(path, "CHECKING_INVENTORY", admin, 200);
        call("PATCH", path + "/reservation", admin, reserve, 409);
        String nextId = UUID.randomUUID().toString();
        call("PATCH", path + "/reservation", admin, Map.of("reservationId", nextId, "status", "RESERVED"), 200);
        call("PATCH", path + "/reservation", admin, Map.of("reservationId", firstId, "status", "RELEASED"), 409);
        call("PATCH", path + "/reservation", admin, Map.of("reservationId", nextId, "status", "RELEASED"), 200);
        status(path, "CANCELLED", admin, 200);
    }

    @Test
    void servesTheCanonicalContractAndSwaggerAssets() throws Exception {
        for (String path : List.of("/swagger-ui/index.html", "/swagger-ui/kestrel.js",
                "/webjars/swagger-ui/5.36.0/swagger-ui-bundle.js", "/maintenance.openapi.yaml")) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(200);
            if (path.endsWith(".yaml")) {
                assertThat(response.body()).contains("openapi: 3.0.3", "- url: /api/v1");
            }
        }
    }

    private JsonNode login(String username) throws Exception {
        return call("POST", "/auth/login", null, Map.of("username", username, "password", PASSWORD), 200);
    }

    private void status(String path, String status, String token, int expected) throws Exception {
        call("PATCH", path + "/status", token, Map.of("status", status), expected);
    }

    private JsonNode call(String method, String path, String token, Object body, int expected) throws Exception {
        var response = http.send(request(method, path, token, body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(expected);
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    private HttpRequest request(String method, String path, String token, Object body) throws IOException {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
    }

    private static void writePem(Path path, String label, byte[] content) throws IOException {
        Files.writeString(path, "-----BEGIN " + label + "-----\n"
                + Base64.getEncoder().encodeToString(content) + "\n-----END " + label + "-----\n");
        path.toFile().deleteOnExit();
    }
}
