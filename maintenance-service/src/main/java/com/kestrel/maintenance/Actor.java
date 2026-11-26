package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

record Actor(UUID id, Role role, String source) {
    static Actor from(Jwt jwt, HttpServletRequest request) {
        return new Actor(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString("role")),
                request.getRemoteAddr());
    }

    boolean technician() {
        return role == Role.TECHNICIAN;
    }
}
