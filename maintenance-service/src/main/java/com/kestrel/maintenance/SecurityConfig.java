package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;
import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    static final String ISSUER = "kestrel-maintenance";
    static final String AUDIENCE = "kestrel-services";
    static final String OPERATORS = "hasAnyRole('ADMIN', 'MAINTENANCE_OPERATOR')";
    static final String MAINTENANCE_READ = "hasAnyRole('ADMIN', 'MAINTENANCE_OPERATOR', 'TECHNICIAN')";

    record SigningKeys(RSAPublicKey publicKey, RSAPrivateKey privateKey) {}

    @Bean
    SigningKeys signingKeys(@Value("${kestrel.keys-dir}") Path directory) throws Exception {
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        try {
            var publicKey = (RSAPublicKey) rsa.generatePublic(new X509EncodedKeySpec(
                    readPem(directory.resolve("public.pem"), "PUBLIC KEY")));
            var privateKey = (RSAPrivateKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(
                    readPem(directory.resolve("private.pem"), "PRIVATE KEY")));
            if (!publicKey.getModulus().equals(privateKey.getModulus()) || publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("The RSA signing keys must form a matching pair of at least 2048 bits.");
            }
            return new SigningKeys(publicKey, privateKey);
        } catch (IOException missing) {
            throw new IllegalStateException("Cannot read Kestrel signing keys. Run scripts/gen-keys first.", missing);
        }
    }

    private byte[] readPem(Path path, String label) throws IOException {
        return Base64.getDecoder().decode(Files.readString(path)
                .replace("-----BEGIN " + label + "-----", "").replace("-----END " + label + "-----", "")
                .replaceAll("\\s+", ""));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeys keys) {
        var jwk = new RSAKey.Builder(keys.publicKey()).privateKey(keys.privateKey()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    @Bean
    JwtDecoder jwtDecoder(SigningKeys keys, Clock clock) {
        var decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER), jwt -> validateClaims(jwt, clock)));
        return decoder;
    }

    private static OAuth2TokenValidatorResult validateClaims(Jwt jwt, Clock clock) {
        try {
            UUID.fromString(jwt.getSubject());
            Role.valueOf(jwt.getClaimAsString("role"));
            if (jwt.getAudience() != null && jwt.getAudience().contains(AUDIENCE)
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(clock.instant())
                    && jwt.getId() != null && !jwt.getId().isBlank()) {
                return OAuth2TokenValidatorResult.success();
            }
        } catch (RuntimeException malformedClaim) {
            // Return the same client-visible error for malformed and missing claims.
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectMapper mapper) throws Exception {
        var unauthorized = (org.springframework.security.web.AuthenticationEntryPoint) (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            writeError(mapper, response, 401, "UNAUTHENTICATED", "A valid access token is required.");
        };
        var forbidden = (org.springframework.security.web.access.AccessDeniedHandler) (request, response, exception) ->
                writeError(mapper, response, 403, "FORBIDDEN", "You do not have permission for this operation.");

        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET, "/swagger-ui/**", "/webjars/swagger-ui/**",
                                "/maintenance.openapi.yaml").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> new JwtAuthenticationToken(token,
                                List.of(new SimpleGrantedAuthority("ROLE_" + token.getClaimAsString("role")))))))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .build();
    }

    private static void writeError(ObjectMapper mapper, HttpServletResponse response, int status,
                                   String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), new ProblemHandler.ErrorBody(code, message, null));
    }
}
