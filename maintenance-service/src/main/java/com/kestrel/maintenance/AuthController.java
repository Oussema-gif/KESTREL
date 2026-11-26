package com.kestrel.maintenance;

import static com.kestrel.maintenance.ApiModels.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AuthService authentication;

    AuthController(AuthService authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/login")
    ResponseEntity<Tokens> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servlet) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(authentication.login(request, servlet.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    ResponseEntity<Tokens> refresh(@Valid @RequestBody RefreshRequest request, HttpServletRequest servlet) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(authentication.refresh(request, servlet.getRemoteAddr()));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request, HttpServletRequest servlet) {
        authentication.logout(request, servlet.getRemoteAddr());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/me")
    UserProfile me(@AuthenticationPrincipal Jwt jwt) {
        return authentication.profile(UUID.fromString(jwt.getSubject()));
    }
}
