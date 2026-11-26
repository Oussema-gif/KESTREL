package com.kestrel.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kestrel.telemetry.MachineRequest;
import com.kestrel.telemetry.SetSimulationScenarioRequest;
import com.kestrel.telemetry.SetSimulationScenarioResponse;
import com.kestrel.telemetry.SimulationControlServiceGrpc;
import com.kestrel.telemetry.SimulationScenario;
import com.kestrel.telemetry.TelemetryReading;
import com.kestrel.telemetry.TelemetryServiceGrpc;
import com.kestrel.telemetry.TelemetrySnapshot;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

final class Gateways implements AutoCloseable {
    interface Maintenance {
        JsonNode get(String path, String token) throws Exception;
        Tokens login(String username, String password) throws Exception;
        Tokens refresh(String refreshToken) throws Exception;
        Profile profile(String accessToken) throws Exception;
        void logout(String refreshToken) throws Exception;
        JsonNode list(String resource, int page, int size, String status, String token) throws Exception;
        JsonNode list(String resource, int page, int size, String status, String machine, String token) throws Exception;
        JsonNode create(String resource, Map<String, ?> body, String token) throws Exception;
        JsonNode patch(String resource, Map<String, ?> body, String token) throws Exception;
    }

    interface Inventory {
        int available(String part, int quantity, String token) throws Exception;
        Reservation reserve(String part, int quantity, String order, String requestId, String token) throws Exception;
        void release(String reservationId, String token) throws Exception;
        int checkBearing(String accessToken) throws Exception;
        Part part(String id, String accessToken) throws Exception;
    }

    interface Telemetry {
        TelemetrySnapshot snapshot(String machine, String accessToken);
        SetSimulationScenarioResponse setScenario(String machine, SimulationScenario scenario, String accessToken);
        void watch(String machine, String accessToken, StreamObserver<TelemetryReading> observer);
    }

    record Tokens(String access, String refresh, Instant expiresAt) {}
    record Profile(String fullName, String role) {}
    record Reservation(String id, String status) {}

    static final class InventoryFault extends IOException {
        private final String code;

        InventoryFault(String code) {
            super("Inventory returned a SOAP fault.");
            this.code = code;
        }

        String code() {
            return code;
        }
    }
    record Part(String sku, String name, String description, String unit, int available, int reorderLevel) {}

    static final class Rejected extends IOException {
        private final int status;

        Rejected(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final URI REST = URI.create("http://127.0.0.1:8081/api/v1/");
    private static final URI SOAP = URI.create("http://127.0.0.1:8082/services/inventory");
    private static final String NS = "urn:kestrel:inventory:v1";
    private static final Metadata.Key<String> AUTH =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper();
    private final ManagedChannel channel =
            NettyChannelBuilder.forAddress("127.0.0.1", 9090).usePlaintext().build();

    final Maintenance maintenance = new Maintenance() {
        @Override
        public JsonNode get(String path, String token) throws Exception {
            return rest(path, token, null);
        }

        @Override
        public Tokens login(String username, String password) throws Exception {
            return readTokens(rest("auth/login", null, Map.of("username", username, "password", password)));
        }

        @Override
        public Tokens refresh(String refreshToken) throws Exception {
            return readTokens(rest("auth/refresh", null, Map.of("refreshToken", refreshToken)));
        }

        @Override
        public Profile profile(String accessToken) throws Exception {
            JsonNode user = rest("auth/me", accessToken, null);
            return new Profile(user.path("fullName").asText(), user.path("role").asText());
        }

        @Override
        public void logout(String refreshToken) throws Exception {
            rest("auth/logout", null, Map.of("refreshToken", refreshToken));
        }

        @Override
        public JsonNode list(String resource, int page, int size, String status, String token) throws Exception {
            return list(resource, page, size, status, "", token);
        }

        @Override
        public JsonNode list(String resource, int page, int size, String status, String machine, String token) throws Exception {
            String query = resource + "?page=" + page + "&size=" + size;
            if (!status.isEmpty()) {
                query += "&status=" + java.net.URLEncoder.encode(status, java.nio.charset.StandardCharsets.UTF_8);
            }
            if (!machine.isEmpty()) {
                query += "&machineCode=" + java.net.URLEncoder.encode(machine, java.nio.charset.StandardCharsets.UTF_8);
            }
            return rest(query, token, null);
        }

        @Override
        public JsonNode create(String resource, Map<String, ?> body, String token) throws Exception {
            return rest(resource, token, body);
        }

        @Override
        public JsonNode patch(String resource, Map<String, ?> body, String token) throws Exception {
            return rest(resource, token, body, "PATCH");
        }
    };

    final Inventory inventory = new Inventory() {
        @Override
        public int available(String part, int quantity, String token) throws Exception {
            String fields = field("partId", part) + field("requestedQuantity", quantity);
            return Integer.parseInt(required(soap("checkStock", fields, token), "availableQuantity"));
        }

        @Override
        public Reservation reserve(String part, int quantity, String order, String requestId, String token) throws Exception {
            String fields = field("partId", part) + field("quantity", quantity)
                    + field("workOrderId", order) + field("requestId", requestId);
            Document result = soap("reservePart", fields, token);
            return new Reservation(required(result, "reservationId"), required(result, "status"));
        }

        @Override
        public void release(String reservationId, String token) throws Exception {
            String fields = field("reservationId", reservationId) + field("reason", "Work order cancelled before attachment");
            Document result = soap("releaseReservation", fields, token);
            if (!reservationId.equals(required(result, "reservationId"))
                    || !"RELEASED".equals(required(result, "status"))) {
                throw new IOException("Inventory release was not confirmed.");
            }
        }

        // Callers pass canonical UUIDs, positive integers, or fixed text.
        private String field(String name, Object value) {
            return "<i:" + name + ">" + value + "</i:" + name + ">";
        }

        @Override
        public int checkBearing(String accessToken) throws Exception {
            String fields = "<i:partId>00000000-0000-4000-8000-000000000101</i:partId>"
                    + "<i:requestedQuantity>1</i:requestedQuantity>";
            return Integer.parseInt(required(soap("checkStock", fields, accessToken), "availableQuantity"));
        }

        @Override
        public Part part(String id, String accessToken) throws Exception {
            var uuid = java.util.UUID.fromString(id);
            Document result = soap("getSparePart", "<i:partId>" + uuid + "</i:partId>", accessToken);
            return new Part(required(result, "sku"), required(result, "name"), value(result, "description"),
                    required(result, "unit"), Integer.parseInt(required(result, "availableQuantity")),
                    Integer.parseInt(required(result, "reorderLevel")));
        }
    };

    private Document soap(String operation, String fields, String token) throws Exception {
        String envelope = """
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                            xmlns:i="urn:kestrel:inventory:v1">
                  <s:Body><i:%sRequest>%s</i:%sRequest></s:Body>
                </s:Envelope>
                """.formatted(operation, fields, operation);
        HttpRequest request = HttpRequest.newBuilder(SOAP).timeout(TIMEOUT)
                .header("Content-Type", "text/xml; charset=UTF-8")
                .header("SOAPAction", '"' + NS + "/" + operation + '"')
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(envelope)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new Rejected(response.statusCode(), "Inventory rejected the request.");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document result = factory.newDocumentBuilder().parse(new InputSource(new StringReader(response.body())));
        if (response.statusCode() != 200) {
            throw switch (value(result, "code")) {
                case "UNAUTHENTICATED" -> new Rejected(401, "Inventory requires a valid session.");
                case "FORBIDDEN" -> new Rejected(403, "Inventory denied access.");
                case "UNKNOWN_PART" -> new Rejected(404, "Inventory does not contain that part.");
                default -> new InventoryFault(value(result, "code"));
            };
        }
        return result;
    }

    private static String value(Document xml, String field) {
        Node element = xml.getElementsByTagNameNS(NS, field).item(0);
        return element == null ? "" : element.getTextContent().strip();
    }

    private static String required(Document xml, String field) throws IOException {
        String result = value(xml, field);
        if (result.isBlank()) {
            throw new IOException("Inventory response omitted a required field.");
        }
        return result;
    }

    final Telemetry telemetry = new Telemetry() {
        @Override
        public SetSimulationScenarioResponse setScenario(String code, SimulationScenario scenario, String accessToken) {
            var request = SetSimulationScenarioRequest.newBuilder().setMachineCode(code).setScenario(scenario).build();
            return SimulationControlServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(accessToken)))
                    .withDeadlineAfter(3, TimeUnit.SECONDS).setSimulationScenario(request);
        }

        @Override
        public TelemetrySnapshot snapshot(String machine, String accessToken) {
            return TelemetryServiceGrpc.newBlockingStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(accessToken)))
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .getMachineSnapshot(machine(machine));
        }

        @Override
        public void watch(String machine, String accessToken, StreamObserver<TelemetryReading> observer) {
            TelemetryServiceGrpc.newStub(channel)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(accessToken)))
                    .withDeadlineAfter(10, TimeUnit.MINUTES)
                    .streamMachineTelemetry(machine(machine), observer);
        }
    };

    private JsonNode rest(String path, String token, Map<String, ?> body) throws Exception {
        return rest(path, token, body, body == null ? "GET" : "POST");
    }

    private JsonNode rest(String path, String token, Map<String, ?> body, String method) throws Exception {
        var request = HttpRequest.newBuilder(REST.resolve(path)).timeout(TIMEOUT)
                .header("Accept", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        }
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new Rejected(status, switch (status) {
                case 401 -> "Check your credentials or sign in again.";
                case 403 -> "This account cannot perform the requested operation.";
                case 429 -> "Too many attempts. Wait before signing in again.";
                default -> "Maintenance returned HTTP " + status + ".";
            });
        }
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    private static Tokens readTokens(JsonNode payload) throws IOException {
        String access = payload.path("accessToken").asText();
        String refresh = payload.path("refreshToken").asText();
        long seconds = payload.path("expiresIn").asLong();
        if (access.isBlank() || refresh.isBlank() || seconds < 1) {
            throw new IOException("Maintenance returned an incomplete session.");
        }
        return new Tokens(access, refresh, Instant.now().plusSeconds(seconds));
    }

    private static Metadata headers(String token) {
        Metadata metadata = new Metadata();
        metadata.put(AUTH, "Bearer " + token);
        return metadata;
    }

    private static MachineRequest machine(String code) {
        return MachineRequest.newBuilder().setMachineCode(code).build();
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}
