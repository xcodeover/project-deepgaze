package com.deepgaze.security.api;

import com.deepgaze.security.JwtService;
import com.deepgaze.security.UserRow;
import com.deepgaze.security.UserStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public + private auth endpoints.
 *
 *   POST /api/auth/login    public  — issues a JWT on valid username+password
 *   GET  /api/auth/me       private — returns the current session's identity,
 *                                     used by the SPA to re-hydrate after a
 *                                     reload when a token is still in
 *                                     localStorage
 *
 * Login timing is kept constant-ish by always running the BCrypt check — we
 * compare against a throwaway hash when the user doesn't exist so attackers
 * can't use response time to enumerate usernames.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** Valid BCrypt hash of the string "does-not-matter" — used for constant-time username enumeration guard. */
    private static final String DUMMY_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoO6A.ZlB6rPpZ/NhABOeEK2Kp4GQUlK4i";

    private final UserStore users;
    private final BCryptPasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(UserStore users, BCryptPasswordEncoder encoder, JwtService jwt) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body) {
        if (body == null || body.username() == null || body.password() == null
                || body.username().isBlank() || body.password().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username and password are required"));
        }

        UserRow user = users.findByUsername(body.username());
        String storedHash = user != null && user.enabled() ? user.passwordHash() : DUMMY_HASH;
        boolean ok = encoder.matches(body.password(), storedHash) && user != null && user.enabled();
        if (!ok) {
            log.info("Login rejected for username='{}'", body.username());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }

        String token = jwt.issue(user.username(), user.role());
        log.info("Login ok: username='{}' role='{}'", user.username(), user.role());
        return ResponseEntity.ok(new LoginResponse(
                token,
                jwt.ttlSeconds(),
                new UserDto(user.username(), user.role())
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(new UserDto(String.valueOf(auth.getPrincipal()), roleOf(auth)));
    }

    private static String roleOf(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst()
                .orElse("USER");
    }

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, long expiresInSeconds, UserDto user) {}
    public record UserDto(String username, String role) {}
}
