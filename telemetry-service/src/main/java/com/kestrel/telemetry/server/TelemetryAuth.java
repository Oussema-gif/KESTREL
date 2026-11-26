package com.kestrel.telemetry.server;

import com.kestrel.telemetry.SimulationControlServiceGrpc;
import com.kestrel.telemetry.TelemetryServiceGrpc;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

final class TelemetryAuth implements ServerInterceptor {
    private static final String ISSUER = "kestrel-maintenance";
    private static final String AUDIENCE = "kestrel-services";
    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Set<String> READ_ROLES = Set.of(
            "ADMIN", "MAINTENANCE_OPERATOR", "TECHNICIAN", "INVENTORY_OPERATOR");
    private static final Set<String> CONTROL_ROLES = Set.of("ADMIN", "MAINTENANCE_OPERATOR");

    static final Context.Key<Instant> ACCESS_EXPIRY = Context.key("kestrel-access-token-expiry");

    private final JwtDecoder decoder;

    TelemetryAuth(Path publicKey, Clock clock) throws Exception {
        String pem;
        try {
            pem = Files.readString(publicKey);
        } catch (IOException missing) {
            throw new IllegalStateException("Cannot read the Telemetry JWT public key. Run scripts/gen-keys or set KESTREL_JWT_PUBLIC_KEY.",
                    missing);
        }
        byte[] encoded = Base64.getDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s+", ""));
        var rsa = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        if (rsa.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("Telemetry requires an RSA public key of at least 2048 bits.");
        }
        var verifier = NimbusJwtDecoder.withPublicKey(rsa).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        verifier.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), jwt -> validate(jwt, clock)));
        decoder = verifier;
    }

    @Override
    public <Req, Resp> ServerCall.Listener<Req> interceptCall(
            ServerCall<Req, Resp> call, Metadata headers, ServerCallHandler<Req, Resp> next) {
        Jwt jwt = authenticate(headers);
        if (jwt == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("A valid access token is required."), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        String service = call.getMethodDescriptor().getServiceName();
        String role = jwt.getClaimAsString("role");
        boolean allowed = switch (service) {
            case TelemetryServiceGrpc.SERVICE_NAME -> READ_ROLES.contains(role);
            case SimulationControlServiceGrpc.SERVICE_NAME -> CONTROL_ROLES.contains(role);
            default -> false;
        };
        if (!allowed) {
            call.close(Status.PERMISSION_DENIED.withDescription("You do not have permission for this operation."), new Metadata());
            return new ServerCall.Listener<>() {};
        }

        Context authenticated = Context.current().withValue(ACCESS_EXPIRY, jwt.getExpiresAt());
        return Contexts.interceptCall(authenticated, call, headers, next);
    }

    private Jwt authenticate(Metadata headers) {
        Iterable<String> values = headers.getAll(AUTHORIZATION);
        if (values == null) {
            return null;
        }
        var entries = values.iterator();
        if (!entries.hasNext()) {
            return null;
        }
        String header = entries.next();
        if (entries.hasNext() || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        try {
            return decoder.decode(header.substring(7).strip());
        } catch (JwtException | IllegalArgumentException rejected) {
            return null;
        }
    }

    private static OAuth2TokenValidatorResult validate(Jwt jwt, Clock clock) {
        try {
            UUID id = UUID.fromString(jwt.getSubject());
            String role = jwt.getClaimAsString("role");
            if (id.toString().equals(jwt.getSubject())
                    && role != null && !role.isBlank()
                    && jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(clock.instant())
                    && jwt.getId() != null && !jwt.getId().isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException malformedClaim) {
            // Missing and malformed token claims use the same public status.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }
}
