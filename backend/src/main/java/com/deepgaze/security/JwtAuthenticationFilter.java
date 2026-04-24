package com.deepgaze.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Servlet JWT filter. Pulls the token out of the request, verifies it, and
 * populates the SecurityContext so downstream authorization rules (and
 * {@code @PreAuthorize}) can read the principal.
 *
 * Two token sources in priority order:
 *   1. {@code Authorization: Bearer <token>} — normal fetch() path.
 *   2. {@code ?token=<jwt>} query parameter — SSE fallback (EventSource
 *      cannot set headers). Only consulted when the Authorization header
 *      is absent.
 *
 * We do NOT reject on bad/absent token here. Leaving the SecurityContext
 * empty lets the authorization step in SecurityConfig produce the 401 —
 * so permitAll paths ({@code /api/auth/login}, static assets, actuator
 * health) still work without a token.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwt;

    public JwtAuthenticationFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    /**
     * Several controllers return {@code Mono<ResponseEntity<...>>}; Spring MVC
     * bridges those via async-dispatch, which re-runs the filter chain after
     * the Mono completes. With stateless auth the SecurityContext isn't
     * preserved across the boundary, so we re-authenticate from the still-
     * attached Bearer header on the async pass too — otherwise the authorize
     * step on the async dispatch sees an empty context and 401s.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = extractToken(request);
            if (token != null) {
                Claims claims = jwt.parseOrNull(token);
                if (claims != null) {
                    String username = claims.getSubject();
                    String role = claims.get("role", String.class);
                    if (role == null || role.isBlank()) role = "USER";
                    var auth = new UsernamePasswordAuthenticationToken(
                            username, token,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        }
        chain.doFilter(request, response);
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            String t = header.substring(BEARER.length()).trim();
            if (!t.isEmpty()) return t;
        }
        // SSE fallback: EventSource cannot set headers, so /api/stream/** relies
        // on a signed token in the URL. Query-string secrets leak into access
        // logs, which we accept as a tradeoff — the token is short-lived and
        // the URL never escapes the browser → same-origin backend hop.
        String q = request.getParameter("token");
        return (q == null || q.isBlank()) ? null : q;
    }
}
