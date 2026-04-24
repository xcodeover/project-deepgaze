package com.deepgaze.ops;

import com.deepgaze.config.DeepGazeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;

/**
 * Gates /api/ops/** behind the shared-secret {@code X-Deepgaze-Auth} header.
 *
 * Only /api/ops/** is guarded — all other endpoints (SSE metrics, session
 * detail, alerts) stay open on the LAN because this service has never had
 * user-level auth and adding it across the whole API is out of scope for
 * the Kill Session feature. The ops prefix is the mutation boundary.
 *
 * Comparison uses MessageDigest.isEqual to avoid short-circuiting string
 * compare timing. The token itself is never logged; unauthorised attempts
 * are logged with principal=anonymous so the audit trail still shows them.
 *
 * Implemented as a servlet {@link OncePerRequestFilter} because the app
 * runs on Tomcat + Spring MVC — WebFlux WebFilters are silently ignored in
 * this stack.
 */
@Slf4j
@Component
@Order(-100)
public class OpsAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Deepgaze-Auth";
    private static final String PREFIX = "/api/ops/";
    private static final Logger AUDIT = LoggerFactory.getLogger("com.deepgaze.ops.audit");

    private final DeepGazeProperties props;

    public OpsAuthFilter(DeepGazeProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // Let CORS preflight through — the browser sends OPTIONS without the
        // custom X-Deepgaze-Auth header, so gating it here would block every
        // subsequent POST from a cross-origin dashboard.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        if (!props.ops().isEnabled()) {
            reject(response, HttpStatus.SERVICE_UNAVAILABLE,
                    "ops_disabled", path, "deepgaze.ops.auth-token is blank");
            return;
        }

        String token = request.getHeader(HEADER);
        if (token == null) token = "";
        if (!constantTimeEquals(token, props.ops().authToken())) {
            reject(response, HttpStatus.UNAUTHORIZED,
                    "auth_failed", path,
                    token.isEmpty() ? "missing X-Deepgaze-Auth header" : "bad token");
            return;
        }

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, HttpStatus status,
                        String reason, String path, String detail) throws IOException {
        AUDIT.warn("[OPS-AUDIT] ts={} action=auth path={} result={} principal=anonymous reason=\"{}\"",
                java.time.Instant.now(), path, reason, detail);
        response.setStatus(status.value());
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ba = a.getBytes();
        byte[] bb = b.getBytes();
        if (ba.length != bb.length) return false;
        return MessageDigest.isEqual(ba, bb);
    }
}
