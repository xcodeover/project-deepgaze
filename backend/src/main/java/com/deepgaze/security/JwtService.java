package com.deepgaze.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Signs + verifies stateless HS256 JWTs. Key material is derived from
 * {@code deepgaze.security.jwt-secret} (env {@code DEEPGAZE_JWT_SECRET}) — we
 * hash it to 256 bits so any operator-supplied string is accepted without
 * coupling the UX to a key-length rule.
 *
 * If the secret is absent we fail fast at startup rather than silently
 * issuing unsigned tokens.
 *
 * Claims:
 *   sub  → username
 *   role → role string (ADMIN today)
 *   iat  → issued-at seconds
 *   exp  → expiry seconds
 *
 * The filter treats an expired or unverifiable token as anonymous.
 */
@Slf4j
@Component
public class JwtService {

    private final String secretRaw;
    private final Duration ttl;
    private SecretKey key;

    public JwtService(
            @Value("${deepgaze.security.jwt-secret:}") String secretRaw,
            @Value("${deepgaze.security.jwt-ttl-minutes:720}") long ttlMinutes
    ) {
        this.secretRaw = secretRaw;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    @PostConstruct
    void init() throws Exception {
        if (secretRaw == null || secretRaw.isBlank()) {
            throw new IllegalStateException(
                    "deepgaze.security.jwt-secret (or DEEPGAZE_JWT_SECRET) is required — " +
                    "refusing to start without it so we never issue unsigned tokens.");
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(secretRaw.getBytes(StandardCharsets.UTF_8));
        this.key = new SecretKeySpec(digest, "HmacSHA256");
        log.info("JwtService ready (ttl={} min)", ttl.toMinutes());
    }

    public String issue(String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    /** Returns parsed claims or null on any verification failure. */
    public Claims parseOrNull(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.debug("JWT verification failed: {}", e.toString());
            return null;
        }
    }

    public long ttlSeconds() {
        return ttl.getSeconds();
    }
}
