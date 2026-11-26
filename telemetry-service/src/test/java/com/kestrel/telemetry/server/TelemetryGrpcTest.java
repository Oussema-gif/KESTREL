package com.kestrel.telemetry.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kestrel.telemetry.MachineRequest;
import com.kestrel.telemetry.SetSimulationScenarioRequest;
import com.kestrel.telemetry.SimulationControlServiceGrpc;
import com.kestrel.telemetry.SimulationScenario;
import com.kestrel.telemetry.TelemetryReading;
import com.kestrel.telemetry.TelemetryServiceGrpc;
import com.kestrel.telemetry.TelemetrySnapshot;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TelemetryGrpcTest {
    private static final MachineRequest MACHINE = machine("MACHINE-001");
    private static final String AUDIENCE = "kestrel-services";
    private static final String ISSUER = "kestrel-maintenance";

    @TempDir
    static Path temp;
    private static JwtEncoder issuer;
    private static Path publicKey;

    @BeforeAll
    static void setupKeys() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        var rsa = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate()).build();
        issuer = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsa)));
        publicKey = temp.resolve("public.pem");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n");
    }

    @Test
    void validatesRequestsAndAuthorizesDataAndControlSeparately() throws Exception {
        try (var fixture = new Fixture(SimulationScenario.NORMAL)) {
            String tech = token("TECHNICIAN");
            assertThat(fixture.data(tech).getMachineSnapshot(MACHINE).getMachineCode()).isEqualTo("MACHINE-001");
            for (String role : List.of("ADMIN", "MAINTENANCE_OPERATOR", "INVENTORY_OPERATOR")) {
                assertThat(fixture.data(token(role)).getMachineSnapshot(MACHINE).getMachineStatus()).isEqualTo("RUNNING");
            }
            status(Status.Code.INVALID_ARGUMENT, () -> fixture.data(tech).getMachineSnapshot(machine("machine-001")));
            status(Status.Code.NOT_FOUND, () -> fixture.data(tech).getMachineSnapshot(machine("MACHINE-999")));
            status(Status.Code.UNAUTHENTICATED, () -> fixture.data(null).getMachineSnapshot(MACHINE));

            var unauthorizedStream = fixture.stream(null);
            assertThat(unauthorizedStream.end.get(3, TimeUnit.SECONDS)).isEqualTo(Status.Code.UNAUTHENTICATED);
            status(Status.Code.PERMISSION_DENIED, () -> fixture.data(token("VIEWER")).getMachineSnapshot(MACHINE));
            status(Status.Code.PERMISSION_DENIED, () -> fixture.control(tech, SimulationScenario.HIGH_TEMPERATURE));
            status(Status.Code.PERMISSION_DENIED, () -> fixture.control(token("INVENTORY_OPERATOR"), SimulationScenario.NORMAL));
            status(Status.Code.INVALID_ARGUMENT, () -> fixture.control(token("ADMIN"), SimulationScenario.SCENARIO_UNSPECIFIED));
            assertThat(fixture.control(token("ADMIN"), SimulationScenario.NORMAL)).isEqualTo(SimulationScenario.NORMAL);

            int signature = tech.lastIndexOf('.') + 1;
            String modified = tech.substring(0, signature) + (tech.charAt(signature) == 'A' ? 'B' : 'A')
                    + tech.substring(signature + 1);
            status(Status.Code.UNAUTHENTICATED, () -> fixture.data(modified).getMachineSnapshot(MACHINE));
            String wrongAudience = token("ADMIN", "another-audience", Instant.now().plusSeconds(90));
            status(Status.Code.UNAUTHENTICATED, () -> fixture.data(wrongAudience).getMachineSnapshot(MACHINE));
            String expired = token("ADMIN", AUDIENCE, Instant.now().minusSeconds(1));
            status(Status.Code.UNAUTHENTICATED, () -> fixture.data(expired).getMachineSnapshot(MACHINE));
        }
    }

    @Test
    void generatesAllFourSpikesAndLeavesOtherMachinesIndependent() throws Exception {
        try (var fixture = new Fixture(SimulationScenario.NORMAL)) {
            String token = token("MAINTENANCE_OPERATOR");
            var cases = List.of(
                    new Spike(SimulationScenario.HIGH_TEMPERATURE, TelemetrySnapshot::getTemperatureC, 85),
                    new Spike(SimulationScenario.HIGH_VIBRATION, TelemetrySnapshot::getVibrationMmS, 7),
                    new Spike(SimulationScenario.PRESSURE_SPIKE, TelemetrySnapshot::getPressureBar, 7),
                    new Spike(SimulationScenario.ENERGY_SPIKE, TelemetrySnapshot::getEnergyKw, 15));
            for (Spike spike : cases) {
                assertThat(fixture.control(token, spike.scenario())).isEqualTo(spike.scenario());
                var snapshot = fixture.data(token).getMachineSnapshot(MACHINE);
                assertThat(spike.measurement().apply(snapshot)).isGreaterThan(spike.critical());
                assertThat(snapshot.getMachineStatus()).isEqualTo("CRITICAL");
                assertThat(fixture.data(token).getMachineSnapshot(machine("MACHINE-002")).getMachineStatus()).isEqualTo("RUNNING");
            }
            fixture.control(token, SimulationScenario.NORMAL);
            assertThat(fixture.data(token).getMachineSnapshot(MACHINE).getMachineStatus()).isEqualTo("RUNNING");
        }
    }

    @Test
    void streamsOrderedReadingsAndOneCancelledStreamDoesNotAffectAnother() throws Exception {
        try (var fixture = new Fixture(SimulationScenario.NORMAL)) {
            var first = fixture.stream(token("TECHNICIAN"));
            var second = fixture.stream(token("ADMIN"));
            var a = first.next();
            var b = first.next();
            assertThat(b.getTimestampEpochMs()).isGreaterThan(a.getTimestampEpochMs());
            assertThat(a.getMachineCode()).isEqualTo(b.getMachineCode()).isEqualTo("MACHINE-001");
            assertThat(a.getTemperatureC()).isBetween(50.0, 57.0);
            first.cancel();
            assertThat(first.end.get(3, TimeUnit.SECONDS)).isEqualTo(Status.Code.CANCELLED);

            var seen = second.next();
            assertThat(second.next().getTimestampEpochMs()).isGreaterThan(seen.getTimestampEpochMs());
            second.cancel();
        }
    }

    @Test
    void offlineKeepsTheLastTimestampAndResumesWithoutBackfill() throws Exception {
        try (var fixture = new Fixture(SimulationScenario.NORMAL)) {
            String token = token("MAINTENANCE_OPERATOR");
            var stream = fixture.stream(token);
            stream.next();
            fixture.control(token, SimulationScenario.OFFLINE);
            long cached = fixture.data(token).getMachineSnapshot(MACHINE).getTimestampEpochMs();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
            while (System.nanoTime() < until) {
                var buffered = stream.readings.poll(25, TimeUnit.MILLISECONDS);
                if (buffered != null) {
                    assertThat(buffered.getTimestampEpochMs()).isLessThanOrEqualTo(cached);
                }
            }
            var offline = fixture.data(token).getMachineSnapshot(MACHINE);
            assertThat(offline.getTimestampEpochMs()).isEqualTo(cached);
            assertThat(offline.getMachineStatus()).isEqualTo("OFFLINE");
            assertThat(stream.end).isNotDone();
            fixture.control(token, SimulationScenario.NORMAL);
            assertThat(stream.next().getTimestampEpochMs()).isGreaterThan(cached);
            stream.cancel();
        }
    }

    @Test
    void startingOfflineHasNoSnapshotAndStillExpiresIdleStreams() throws Exception {
        try (var fixture = new Fixture(SimulationScenario.OFFLINE)) {
            String valid = token("ADMIN");
            status(Status.Code.FAILED_PRECONDITION, () -> fixture.data(valid).getMachineSnapshot(MACHINE));
            Instant expiry = Instant.now().plusSeconds(30);
            var idle = fixture.stream(token("ADMIN", AUDIENCE, expiry));
            assertThat(idle.readings.poll(200, TimeUnit.MILLISECONDS)).isNull();
            assertThat(idle.end).isNotDone();
            fixture.time.set(expiry.plusSeconds(1));
            assertThat(idle.end.get(3, TimeUnit.SECONDS)).isEqualTo(Status.Code.UNAUTHENTICATED);
        }
    }

    @Test
    void gracefulShutdownEndsExistingStreams() throws Exception {
        try (var fixture = new Fixture(SimulationScenario.NORMAL)) {
            var stream = fixture.stream(token("ADMIN"));
            stream.next();
            fixture.server.close();
            assertThat(stream.end.get(3, TimeUnit.SECONDS)).isEqualTo(Status.Code.UNAVAILABLE);
        }
    }

    @Test
    void customThresholdsChangeTheReportedStatusAndRejectInvalidBounds() throws Exception {
        try (var fixture = new Fixture(SimulationScenario.NORMAL, new TelemetrySettings.Threshold(40, 100))) {
            assertThat(fixture.data(token("ADMIN")).getMachineSnapshot(MACHINE).getMachineStatus()).isEqualTo("WARNING");
        }
        assertThatThrownBy(() -> new TelemetrySettings.Threshold(9, 8)).isInstanceOf(IllegalArgumentException.class);
    }

    private record Spike(SimulationScenario scenario, Function<TelemetrySnapshot, Double> measurement, double critical) {}

    private static MachineRequest machine(String code) {
        return MachineRequest.newBuilder().setMachineCode(code).build();
    }

    private static String token(String role) {
        return token(role, AUDIENCE, Instant.now().plusSeconds(90));
    }

    private static String token(String role, String audience, Instant expiry) {
        var claims = JwtClaimsSet.builder().issuer(ISSUER).audience(List.of(audience)).claim("role", role)
                .subject(UUID.randomUUID().toString()).id(UUID.randomUUID().toString())
                .issuedAt(Instant.now().minusSeconds(10)).expiresAt(expiry).build();
        return issuer.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }

    private static void status(Status.Code expected, Runnable request) {
        assertThatExceptionOfType(StatusRuntimeException.class).isThrownBy(request::run)
                .satisfies(error -> assertThat(error.getStatus().getCode()).isEqualTo(expected));
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.now());
        private final TelemetrySimulator simulator;
        private final TelemetryServer server;
        private final ManagedChannel channel;

        Fixture(SimulationScenario initial) throws Exception {
            this(initial, new TelemetrySettings.Threshold(75, 85));
        }

        Fixture(SimulationScenario initial, TelemetrySettings.Threshold temperature) throws Exception {
            var clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
                @Override public Instant instant() { return time.get(); }
            };
            var settings = new TelemetrySettings(0, publicKey, List.of("MACHINE-001", "MACHINE-002"),
                    initial, Duration.ofMillis(50), temperature, new TelemetrySettings.Threshold(4, 7),
                    new TelemetrySettings.Threshold(6, 7), new TelemetrySettings.Threshold(12, 15));
            simulator = new TelemetrySimulator(settings, clock);
            server = new TelemetryServer(settings, simulator, new TelemetryAuth(publicKey, clock));
            channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        }

        TelemetryServiceGrpc.TelemetryServiceBlockingStub data(String token) {
            return TelemetryServiceGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(token)));
        }

        SimulationScenario control(String token, SimulationScenario scenario) {
            return SimulationControlServiceGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(token)))
                    .setSimulationScenario(SetSimulationScenarioRequest.newBuilder().setMachineCode(MACHINE.getMachineCode())
                            .setScenario(scenario).build()).getScenario();
        }

        RecordingStream stream(String token) {
            var record = new RecordingStream();
            TelemetryServiceGrpc.newStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers(token)))
                    .streamMachineTelemetry(MACHINE, record);
            return record;
        }

        private static Metadata headers(String token) {
            Metadata metadata = new Metadata();
            if (token != null) {
                metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
            }
            return metadata;
        }

        @Override
        public void close() throws InterruptedException {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            server.close();
            simulator.close();
        }
    }

    private static final class RecordingStream implements ClientResponseObserver<MachineRequest, TelemetryReading> {
        private final LinkedBlockingQueue<TelemetryReading> readings = new LinkedBlockingQueue<>();
        private final CompletableFuture<Status.Code> end = new CompletableFuture<>();
        private ClientCallStreamObserver<MachineRequest> call;

        @Override public void beforeStart(ClientCallStreamObserver<MachineRequest> request) { call = request; }
        @Override public void onNext(TelemetryReading reading) { readings.add(reading); }
        @Override public void onError(Throwable error) { end.complete(Status.fromThrowable(error).getCode()); }
        @Override public void onCompleted() { end.complete(Status.Code.OK); }

        TelemetryReading next() throws InterruptedException {
            var reading = readings.poll(3, TimeUnit.SECONDS);
            assertThat(reading).isNotNull();
            return reading;
        }

        void cancel() {
            call.cancel("Test client closed the stream.", null);
        }
    }
}
