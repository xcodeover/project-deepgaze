package com.deepgaze.ops;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.ops.strategy.KillStrategy;
import com.deepgaze.targets.TargetRegistry;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Executes KILL against a target DB after a strict preflight check. The only
 * path by which a thread id from the UI is ever turned into a mutation.
 *
 * The flow is intentionally defensive:
 *   1. Resolve the target's ops pool — 503 if the target has no ops credential.
 *   2. Look up the session via the engine-specific {@link KillStrategy}. If
 *      the row no longer exists (session already gone) we return early with
 *      reason=gone.
 *   3. Preflight — reject if the session belongs to the monitoring user, the
 *      ops user itself, or any engine-specific protected user / command.
 *   4. Issue the engine-specific KILL. Capture the pre-kill row so the UI /
 *      audit trail can show who/what got killed.
 *   5. Write one structured audit line per call — success, reject, or failure.
 *
 * Engine differences live in the {@link KillStrategy} implementations under
 * {@code com.deepgaze.ops.strategy}. This service stays engine-agnostic and
 * dispatches by {@link DbTargetConfig#type()}.
 *
 * The audit logger is a separate SLF4J name ({@code com.deepgaze.ops.audit})
 * so operators can route it to its own file via logback without picking up
 * the rest of the DEBUG chatter.
 */
@Slf4j
@Service
public class KillCommandService {

    private static final Logger AUDIT = LoggerFactory.getLogger("com.deepgaze.ops.audit");

    /** Hard upper bound on the preflight SELECT and the KILL itself (seconds, JDBC units). */
    private static final int QUERY_TIMEOUT_SECONDS = 3;

    private final TargetRegistry targets;
    private final OpsDataSourceRegistry opsDataSources;
    private final Map<DbType, KillStrategy> strategies;

    public KillCommandService(TargetRegistry targets,
                              OpsDataSourceRegistry opsDataSources,
                              List<KillStrategy> strategyBeans) {
        this.targets = targets;
        this.opsDataSources = opsDataSources;
        this.strategies = new EnumMap<>(DbType.class);
        for (KillStrategy s : strategyBeans) {
            strategies.put(s.supports(), s);
        }
    }

    public KillOutcome kill(String targetId, long threadId, String principal) {
        DbTargetConfig target = targets.byId(targetId).orElse(null);
        if (target == null) {
            audit(targetId, threadId, principal, "rejected", "unknown target", null, 0);
            return KillOutcome.rejected("Unknown target: " + targetId);
        }

        KillStrategy strategy = strategies.get(target.type());
        if (strategy == null) {
            audit(targetId, threadId, principal, "unavailable",
                    "no kill strategy for engine " + target.type(), null, 0);
            return KillOutcome.unavailable(
                    "Kill Session is not supported for engine " + target.type());
        }

        Optional<HikariDataSource> ds = opsDataSources.dataSourceFor(targetId);
        if (ds.isEmpty()) {
            audit(targetId, threadId, principal, "unavailable", "ops credential not configured", null, 0);
            return KillOutcome.unavailable("Kill Session is not configured for target " + targetId);
        }

        long startNs = System.nanoTime();
        try (Connection c = ds.get().getConnection()) {
            SessionRow row = strategy.lookup(c, threadId, QUERY_TIMEOUT_SECONDS);
            if (row == null) {
                long ms = (System.nanoTime() - startNs) / 1_000_000L;
                audit(targetId, threadId, principal, "gone", "session not found", null, ms);
                return KillOutcome.rejected("Session " + threadId + " no longer exists");
            }

            String rejection = strategy.preflight(row, target);
            if (rejection != null) {
                long ms = (System.nanoTime() - startNs) / 1_000_000L;
                audit(targetId, threadId, principal, "rejected", rejection, row, ms);
                return KillOutcome.rejected(rejection);
            }

            strategy.kill(c, row, QUERY_TIMEOUT_SECONDS);
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            audit(targetId, threadId, principal, "ok", "killed", row, ms);
            return KillOutcome.ok(row);
        } catch (Exception e) {
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            String msg = rootMessage(e);
            audit(targetId, threadId, principal, "failed", msg, null, ms);
            return KillOutcome.failed(msg);
        }
    }

    private void audit(String targetId, long threadId, String principal, String result,
                       String reason, SessionRow row, long durationMs) {
        String user    = row == null ? "-" : safe(row.user());
        String host    = row == null ? "-" : safe(row.host());
        String command = row == null ? "-" : safe(row.command());
        long sec       = row == null ? -1  : row.timeSecs();
        AUDIT.info("[OPS-AUDIT] ts={} action=kill target={} thread={} principal={} result={} " +
                   "session_user={} session_host={} session_command={} session_time_s={} duration_ms={} reason=\"{}\"",
                Instant.now(), targetId, threadId, safe(principal), result,
                user, host, command, sec, durationMs, reason);
    }

    private static String safe(String s) {
        if (s == null || s.isBlank()) return "-";
        return s.replace('"', '\'').replace('\n', ' ');
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
    }

    /**
     * Engine-agnostic session descriptor. {@code serial} is only meaningful
     * for Oracle (where {@code ALTER SYSTEM KILL SESSION} needs the SID,SERIAL#
     * pair to disambiguate SID reuse); MariaDB and MSSQL populate it as 0.
     */
    public record SessionRow(long id, long serial, String user, String host, String db, String command,
                             long timeSecs, String state, String info) {}

    public record KillOutcome(Status status, String message, SessionRow row) {
        public enum Status { OK, REJECTED, UNAVAILABLE, FAILED }
        public static KillOutcome ok(SessionRow row)           { return new KillOutcome(Status.OK, "killed", row); }
        public static KillOutcome rejected(String m)           { return new KillOutcome(Status.REJECTED, m, null); }
        public static KillOutcome unavailable(String m)        { return new KillOutcome(Status.UNAVAILABLE, m, null); }
        public static KillOutcome failed(String m)             { return new KillOutcome(Status.FAILED, m, null); }
    }
}
