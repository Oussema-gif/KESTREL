package com.kestrel.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.w3c.dom.Document;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "KESTREL_RUN_INVENTORY_DB_TESTS", matches = "true")
class InventorySoapTest {
    private static final UUID DEMO_PART = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final String NAMESPACE = "urn:kestrel:inventory:v1";
    private static JwtEncoder encoder;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;
    @Autowired
    JdbcClient db;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        String override = System.getenv("KESTREL_INVENTORY_TEST_DB_URL");
        if (override == null || override.isBlank()) {
            var postgres = new PostgreSQLContainer<>("postgres:16");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
            registry.add("spring.flyway.create-schemas", () -> true);
        } else {
            registry.add("spring.datasource.url", () -> override);
            registry.add("spring.datasource.username", () -> System.getenv("KESTREL_INVENTORY_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("KESTREL_INVENTORY_TEST_DB_PASSWORD"));
        }

        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).build();
        encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        Path publicKey = Files.createTempFile("kestrel-inventory-test-", ".pem");
        publicKey.toFile().deleteOnExit();
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
        registry.add("kestrel.jwt-public-key", publicKey::toString);
    }

    @Test
    void publishesWsdlAndReportsReadOnlyStock() throws Exception {
        var wsdl = http.send(HttpRequest.newBuilder(address("?wsdl")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(wsdl.statusCode()).isEqualTo(200);
        assertThat(wsdl.body()).contains("InventoryService", "reservePart", "releaseReservation");
        Document contract = parse(wsdl.body());
        var imported = contract.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "import").item(0);
        String schema = imported.getAttributes().getNamedItem("schemaLocation").getNodeValue();
        URI schemaUri = address("?wsdl").resolve(schema);
        var xsd = http.send(HttpRequest.newBuilder(schemaUri).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(xsd.statusCode()).isEqualTo(200);
        assertThat(xsd.body()).contains("PositiveQuantity", "inventoryFault");

        String tech = token("TECHNICIAN");
        assertThat(ok(call("getSparePart", part(DEMO_PART), tech), "sku")).isEqualTo("BRG-6205");
        var actual = call("checkStock", part(DEMO_PART) + tag("requestedQuantity", 13), tech);
        assertThat(ok(actual, "available")).isEqualTo("false");
        assertThat(field(actual, "availableQuantity")).isEqualTo("12");
        assertThat(stock(DEMO_PART)).isEqualTo(12);
    }

    @Test
    void checksTokenTrustAndOperationRoles(CapturedOutput output) throws Exception {
        String technician = token("TECHNICIAN");
        var check = part(DEMO_PART) + tag("requestedQuantity", 1);
        fault(call("checkStock", check, null), "UNAUTHENTICATED");
        int signature = technician.lastIndexOf('.') + 1;
        char alternative = technician.charAt(signature) == 'A' ? 'B' : 'A';
        String tampered = technician.substring(0, signature) + alternative + technician.substring(signature + 1);
        fault(call("checkStock", check, tampered), "UNAUTHENTICATED");
        String expired = token("TECHNICIAN", "kestrel-services", Instant.now().minusSeconds(2));
        fault(call("checkStock", check, expired), "UNAUTHENTICATED");
        String wrongAudience = token("TECHNICIAN", "another-service", Instant.now().plusSeconds(90));
        fault(call("checkStock", check, wrongAudience), "UNAUTHENTICATED");

        var reserve = reservation(DEMO_PART, 1, UUID.randomUUID(), UUID.randomUUID());
        fault(call("reservePart", reserve, technician), "FORBIDDEN");
        fault(call("releaseReservation", release(UUID.randomUUID().toString()), technician), "FORBIDDEN");
        fault(call("createPurchaseOrder", part(DEMO_PART) + tag("quantity", 1) + tag("supplierReference", "Demo"),
                token("MAINTENANCE_OPERATOR")), "FORBIDDEN");
        assertThat(output.getAll()).doesNotContain(technician, tampered, expired, wrongAudience);
    }

    @Test
    void returnsTypedFaultsForBadQuantityUnknownResourceAndShortStock() throws Exception {
        String token = token("ADMIN");
        UUID part = addPart(1);
        fault(call("checkStock", part(part) + tag("requestedQuantity", 0), token), "INVALID_QUANTITY");
        fault(call("checkStock", part(part) + tag("requestedQuantity", "oops"), token), "INVALID_QUANTITY");
        fault(call("getSparePart", tag("partId", "bad-id"), token), "INVALID_REQUEST");
        fault(call("getSparePart", part(UUID.randomUUID()), token), "UNKNOWN_PART");
        fault(call("releaseReservation", release(UUID.randomUUID().toString()), token), "UNKNOWN_RESERVATION");
        var insufficient = call("reservePart", reservation(part, 2, UUID.randomUUID(), UUID.randomUUID()), token);
        fault(insufficient, "INSUFFICIENT_STOCK");
        assertThat(field(insufficient, "availableQuantity")).isEqualTo("1");
        assertThat(stock(part)).isEqualTo(1);
    }

    @Test
    void concurrentReserveAndReleaseReplaysHaveExactlyOneStockEffect() throws Exception {
        UUID part = addPart(6);
        String token = token("MAINTENANCE_OPERATOR");
        var input = reservation(part, 4, UUID.randomUUID(), UUID.randomUUID());

        var a = submit("reservePart", input, token);
        var b = submit("reservePart", input, token);
        String reservation = ok(a.get(), "reservationId");
        assertThat(ok(b.get(), "reservationId")).isEqualTo(reservation);
        assertThat(stock(part)).isEqualTo(2);
        assertThat(movements(reservation)).containsExactly("RESERVE");

        var first = submit("releaseReservation", release(reservation), token);
        var second = submit("releaseReservation", release(reservation), token);
        String releasedAt = ok(first.get(), "releasedAt");
        assertThat(ok(second.get(), "releasedAt")).isEqualTo(releasedAt).endsWith("Z");
        var replay = call("reservePart", input, token);
        assertThat(ok(replay, "status")).isEqualTo("RELEASED");
        assertThat(field(replay, "reservationId")).isEqualTo(reservation);
        assertThat(stock(part)).isEqualTo(6);
        assertThat(movements(reservation)).containsExactlyInAnyOrder("RESERVE", "RELEASE");
    }

    @Test
    void concurrentReservationsDoNotOversellOrReuseAConflictingKey() throws Exception {
        UUID part = addPart(10);
        String token = token("INVENTORY_OPERATOR");
        var attempts = IntStream.range(0, 5).mapToObj(i -> submit("reservePart",
                reservation(part, 8, UUID.randomUUID(), UUID.randomUUID()), token)).toList();
        var responses = attempts.stream().map(CompletableFuture::join).toList();
        assertThat(responses).filteredOn(response -> response.statusCode() == 200).hasSize(1);
        for (var response : responses) {
            if (response.statusCode() != 200) {
                fault(response, "INSUFFICIENT_STOCK");
            }
        }
        assertThat(stock(part)).isEqualTo(2);
        assertThat(db.sql("SELECT count(*) FROM inventory.stock_movements WHERE part_id = :part")
                .param("part", part).query(Long.class).single()).isEqualTo(1L);

        UUID left = addPart(5);
        UUID right = addPart(5);
        UUID shared = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        var a = submit("reservePart", reservation(left, 1, order, shared), token);
        var b = submit("reservePart", reservation(right, 1, order, shared), token);
        var pair = List.of(a.get(), b.get());
        assertThat(pair.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 500);
        fault(pair.stream().filter(response -> response.statusCode() == 500).findFirst().orElseThrow(), "REQUEST_CONFLICT");
        assertThat(stock(left) + stock(right)).isEqualTo(9);
    }

    @Test
    void purchaseOrderCreatesARecordWithoutAddingStock() throws Exception {
        UUID part = addPart(3);
        String request = part(part) + tag("quantity", 7) + tag("supplierReference", "DEMO-SUPPLIER");
        var result = call("createPurchaseOrder", request, token("INVENTORY_OPERATOR"));
        assertThat(ok(result, "status")).isEqualTo("CREATED");
        UUID order = UUID.fromString(field(result, "purchaseOrderId"));
        assertThat(db.sql("SELECT quantity FROM inventory.purchase_orders WHERE id = :id").param("id", order)
                .query(Integer.class).single()).isEqualTo(7);
        assertThat(stock(part)).isEqualTo(3);
    }

    private UUID addPart(int quantity) {
        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO inventory.spare_parts (id, sku, name, unit, available_quantity, reorder_level)
                VALUES (:id, :sku, 'Test part', 'EA', :quantity, 0)
                """).param("id", id).param("sku", id.toString()).param("quantity", quantity).update();
        return id;
    }

    private int stock(UUID id) {
        return db.sql("SELECT available_quantity FROM inventory.spare_parts WHERE id = :id")
                .param("id", id).query(Integer.class).single();
    }

    private List<String> movements(String id) {
        return db.sql("SELECT movement_type FROM inventory.stock_movements WHERE reservation_id = :id")
                .param("id", UUID.fromString(id)).query(String.class).list();
    }

    private URI address(String suffix) {
        return URI.create("http://127.0.0.1:" + port + "/services/inventory" + suffix);
    }

    private HttpResponse<String> call(String operation, String input, String token) throws Exception {
        return http.send(request(operation, input, token), HttpResponse.BodyHandlers.ofString());
    }

    private CompletableFuture<HttpResponse<String>> submit(String operation, String input, String token) {
        return http.sendAsync(request(operation, input, token), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String operation, String input, String token) {
        String xml = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" xmlns:inv="%s">
                    <soap:Body><inv:%sRequest>%s</inv:%sRequest></soap:Body>
                </soap:Envelope>
                """.formatted(NAMESPACE, operation, input, operation);
        var builder = HttpRequest.newBuilder(address("")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "text/xml; charset=UTF-8").header("SOAPAction", '"' + NAMESPACE + '/' + operation + '"');
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.POST(HttpRequest.BodyPublishers.ofString(xml)).build();
    }

    private static String ok(HttpResponse<String> response, String name) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return field(response, name);
    }

    private static void fault(HttpResponse<String> response, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(500);
        assertThat(field(response, "code")).as(response.body()).isEqualTo(code);
    }

    private static String field(HttpResponse<String> response, String name) throws Exception {
        var nodes = parse(response.body()).getElementsByTagNameNS(NAMESPACE, name);
        assertThat(nodes.getLength()).as("Inventory element %s", name).isPositive();
        return nodes.item(0).getTextContent();
    }

    private static Document parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String tag(String name, Object value) {
        return "<inv:" + name + ">" + value + "</inv:" + name + ">";
    }

    private static String part(UUID part) {
        return tag("partId", part);
    }

    private static String reservation(UUID part, int quantity, UUID order, UUID request) {
        return part(part) + tag("quantity", quantity) + tag("workOrderId", order) + tag("requestId", request);
    }

    private static String release(String id) {
        return tag("reservationId", id) + tag("reason", "Demo release");
    }

    private static String token(String role) {
        return token(role, "kestrel-services", Instant.now().plusSeconds(90));
    }

    private static String token(String role, String audience, Instant expires) {
        var claims = JwtClaimsSet.builder().issuer("kestrel-maintenance").audience(List.of(audience))
                .subject(UUID.randomUUID().toString()).issuedAt(Instant.now().minusSeconds(10))
                .expiresAt(expires).id(UUID.randomUUID().toString()).claim("role", role).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
