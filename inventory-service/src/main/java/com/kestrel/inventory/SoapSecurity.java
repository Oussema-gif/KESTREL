package com.kestrel.inventory;

import static com.kestrel.inventory.soap.InventoryFaultCode.FORBIDDEN;
import static com.kestrel.inventory.soap.InventoryFaultCode.UNAUTHENTICATED;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.transport.http.AbstractHTTPDestination;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

final class SoapSecurity {
    private static final String ISSUER = "kestrel-maintenance";
    private static final String AUDIENCE = "kestrel-services";
    private static final String ROLE = SoapSecurity.class.getName() + ".role";
    private static final Set<String> ALL_ROLES = Set.of(
            "ADMIN", "MAINTENANCE_OPERATOR", "TECHNICIAN", "INVENTORY_OPERATOR");

    private SoapSecurity() {
    }

    static JwtDecoder decoder(Path publicKey, Clock clock) throws Exception {
        String pem;
        try {
            pem = Files.readString(publicKey);
        } catch (IOException missing) {
            throw new IllegalStateException("Cannot read Inventory JWT public key. Run scripts/gen-keys or set KESTREL_JWT_PUBLIC_KEY.",
                    missing);
        }
        byte[] encoded = Base64.getDecoder().decode(pem.replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s+", ""));
        var key = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        if (key.getModulus().bitLength() < 2048) {
            throw new IllegalArgumentException("Inventory requires an RSA public key of at least 2048 bits.");
        }
        var decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), jwt -> validateClaims(jwt, clock)));
        return decoder;
    }

    private static OAuth2TokenValidatorResult validateClaims(Jwt jwt, Clock clock) {
        try {
            UUID.fromString(jwt.getSubject());
            String role = jwt.getClaimAsString("role");
            if (role != null && ALL_ROLES.contains(role)
                    && jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(clock.instant())
                    && jwt.getId() != null && !jwt.getId().isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException malformedClaim) {
            // Return the same SOAP fault for invalid and missing claims.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    static final class Authenticate extends AbstractSoapInterceptor {
        private final JwtDecoder decoder;

        Authenticate(JwtDecoder decoder) {
            super(Phase.RECEIVE);
            this.decoder = decoder;
        }

        @Override
        public void handleMessage(SoapMessage message) {
            var request = (HttpServletRequest) message.get(AbstractHTTPDestination.HTTP_REQUEST);
            if (request != null && "GET".equals(request.getMethod())) {
                return;
            }
            try {
                if (request != null) {
                    var headers = request.getHeaders("Authorization");
                    if (headers.hasMoreElements()) {
                        String header = headers.nextElement();
                        if (!headers.hasMoreElements() && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                            Jwt jwt = decoder.decode(header.substring(7).strip());
                            message.getExchange().put(ROLE, jwt.getClaimAsString("role"));
                            return;
                        }
                    }
                }
            } catch (JwtException | IllegalArgumentException invalidToken) {
                // The raw token and decoder exception are never sent to the client.
            }
            throw SoapFaults.sender(UNAUTHENTICATED, "A valid access token is required.");
        }
    }

    static final class Authorize extends AbstractSoapInterceptor {
        Authorize() {
            super(Phase.PRE_INVOKE);
        }

        @Override
        public void handleMessage(SoapMessage message) {
            String role = (String) message.getExchange().get(ROLE);
            if (role == null) {
                throw SoapFaults.sender(UNAUTHENTICATED, "A valid access token is required.");
            }
            var binding = message.getExchange().getBindingOperationInfo();
            String operation = binding == null ? "" : binding.getName().getLocalPart();
            boolean allowed = switch (operation) {
                case "getSparePart", "checkStock" -> ALL_ROLES.contains(role);
                case "reservePart", "releaseReservation" -> !"TECHNICIAN".equals(role) && ALL_ROLES.contains(role);
                case "createPurchaseOrder" -> "ADMIN".equals(role) || "INVENTORY_OPERATOR".equals(role);
                default -> false;
            };
            if (!allowed) {
                throw SoapFaults.sender(FORBIDDEN, "You do not have permission for this operation.");
            }
        }
    }
}
