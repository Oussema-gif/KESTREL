package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuthService {
    private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(15);
    private static final Duration REFRESH_LIFETIME = Duration.ofDays(7);

    private record Account(UUID id, String username, String fullName, Role role, String passwordHash, boolean enabled) {
        UserProfile profile() {
            return new UserProfile(id, username, fullName, role);
        }
    }

    private final JdbcClient db;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final AuditLog audit;
    private final LoginThrottle throttle;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    AuthService(JdbcClient db, PasswordEncoder passwords, JwtEncoder jwtEncoder, Clock clock, AuditLog audit,
                LoginThrottle throttle) {
        this.db = db;
        this.passwords = passwords;
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.audit = audit;
        this.throttle = throttle;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = ApiProblem.class)
    public Tokens login(LoginRequest request, String source) {
        String username = request.username().strip().toLowerCase(Locale.ROOT);
        try {
            throttle.checkSource(source);
            throttle.checkAccount(username);
        } catch (ApiProblem limited) {
            audit.record(null, "LOGIN", "authentication", false, source);
            throw limited;
        }

        Account account = accountBy("username", username).orElse(null);
        String candidate = request.password();
        boolean fitsBcrypt = candidate.getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matches = passwords.matches(fitsBcrypt ? candidate : "invalid-length",
                account != null ? account.passwordHash() : dummyHash);
        if (!fitsBcrypt || !matches || account == null || !account.enabled()) {
            audit.record(account == null ? null : account.id(), "LOGIN", "authentication", false, source);
            throttle.failed(username);
            throw new ApiProblem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password.");
        }

        throttle.succeeded(username);
        Tokens issued = issue(account);
        audit.record(account.id(), "LOGIN", "authentication", true, source);
        return issued;
    }

    @Transactional(noRollbackFor = ApiProblem.class)
    public Tokens refresh(RefreshRequest request, String source) {
        throttle.checkSource(source);
        UUID userId = db.sql("""
                UPDATE maintenance.refresh_tokens SET revoked_at = :now
                WHERE token_hash = :hash AND revoked_at IS NULL AND expires_at > :now
                RETURNING user_id
                """).param("now", OffsetDateTime.now(clock)).param("hash", hash(request.refreshToken()))
                .query(UUID.class).optional().orElse(null);
        Account account = userId == null ? null : accountBy("id", userId).filter(Account::enabled).orElse(null);
        if (account == null) {
            audit.record(userId, "REFRESH", "authentication", false, source);
            throw new ApiProblem(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "The refresh token is invalid or expired.");
        }
        Tokens issued = issue(account);
        audit.record(account.id(), "REFRESH", "authentication", true, source);
        return issued;
    }

    @Transactional
    public void logout(RefreshRequest request, String source) {
        db.sql("""
                UPDATE maintenance.refresh_tokens SET revoked_at = :now
                WHERE token_hash = :hash AND revoked_at IS NULL
                RETURNING user_id
                """).param("now", OffsetDateTime.now(clock)).param("hash", hash(request.refreshToken()))
                .query(UUID.class).optional().ifPresent(id -> audit.record(id, "LOGOUT", "authentication", true, source));
    }

    UserProfile profile(UUID userId) {
        return accountBy("id", userId).filter(Account::enabled).map(Account::profile).orElseThrow(ApiProblem::forbidden);
    }

    private Optional<Account> accountBy(String column, Object value) {
        return db.sql("SELECT * FROM maintenance.users WHERE " + column + " = :value").param("value", value)
                .query((row, index) -> account(row)).optional();
    }

    private static Account account(ResultSet row) throws SQLException {
        return new Account(row.getObject("id", UUID.class), row.getString("username"), row.getString("full_name"),
                Role.valueOf(row.getString("role")), row.getString("password_hash"), row.getBoolean("enabled"));
    }

    private Tokens issue(Account account) {
        Instant now = clock.instant();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.sql("""
                INSERT INTO maintenance.refresh_tokens (token_hash, user_id, expires_at)
                VALUES (:hash, :user, :expires)
                """).param("hash", hash(refresh)).param("user", account.id())
                .param("expires", OffsetDateTime.ofInstant(now.plus(REFRESH_LIFETIME), ZoneOffset.UTC)).update();

        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(SecurityConfig.ISSUER).subject(account.id().toString())
                .audience(List.of(SecurityConfig.AUDIENCE)).issuedAt(now).expiresAt(now.plus(ACCESS_LIFETIME))
                .id(UUID.randomUUID().toString()).claim("role", account.role().name()).build();
        String access = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new Tokens(access, refresh, "Bearer", ACCESS_LIFETIME.toSeconds(), REFRESH_LIFETIME.toSeconds());
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }
}
