package com.deepgaze.targets;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbTargetConfig.HikariSettings;
import com.deepgaze.model.DbTargetConfig.NetworkSettings;
import com.deepgaze.model.DbTargetConfig.OpsSettings;
import com.deepgaze.model.DbType;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates CRUD on top of {@link TargetRegistry}. Validates inputs,
 * builds {@link DbTargetConfig} values from {@link TargetDto} payloads, and
 * wraps the registry's mutation methods with higher-level semantics
 * (partial updates keep the existing password when {@code dto.password()}
 * is null, test-connection opens a one-shot pool, etc.).
 *
 * Keep thin — the registry owns persistence + event publication.
 */
@Slf4j
@Service
public class TargetService {

    private static final int ID_MAX = 64;

    private final TargetRegistry registry;

    public TargetService(TargetRegistry registry) {
        this.registry = registry;
    }

    public List<DbTargetConfig> list() {
        return registry.all();
    }

    /**
     * Admin-facing listing — includes disabled rows that are NOT in the
     * runtime cache so operators can see and re-enable them.
     */
    public List<TargetRow> listAllRows() {
        return registry.allRows();
    }

    public Optional<DbTargetConfig> get(String id) {
        return registry.byId(id);
    }

    public void setEnabled(String id, boolean enabled) {
        registry.setEnabled(id, enabled);
    }

    public DbTargetConfig create(TargetDto dto) {
        validateId(dto.id());
        if (registry.contains(dto.id())) {
            throw new IllegalArgumentException("Target id already exists: " + dto.id());
        }
        require(dto.engine(), "engine");
        require(dto.jdbcUrl(), "jdbcUrl");
        require(dto.username(), "username");
        // password may be blank for unusual auth setups; registry.encrypt() tolerates.
        DbTargetConfig cfg = buildFromDto(dto, /*previous*/ null);
        return registry.add(cfg);
    }

    public DbTargetConfig update(String id, TargetDto dto) {
        DbTargetConfig prev = registry.byId(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown target: " + id));
        DbTargetConfig cfg = buildFromDto(dto, prev);
        return registry.update(cfg);
    }

    public void delete(String id) {
        if (!registry.contains(id)) {
            throw new IllegalArgumentException("Unknown target: " + id);
        }
        registry.remove(id);
    }

    public void reorder(List<TargetStore.OrderUpdate> updates) {
        if (updates == null || updates.isEmpty()) return;
        for (TargetStore.OrderUpdate u : updates) {
            if (u.id() == null || u.id().isBlank()) {
                throw new IllegalArgumentException("reorder entry missing id");
            }
        }
        registry.reorder(updates);
    }

    /**
     * Opens a transient Hikari pool, pulls a single connection, and closes
     * everything. Returns a structured result instead of throwing so the
     * controller can translate gracefully to JSON. Uses the pooled fields
     * from the DTO (falling back to existing values if {@code id} already
     * exists and the DTO omits fields).
     */
    public TestResult testConnection(TargetDto dto) {
        DbTargetConfig prev = dto.id() == null ? null : registry.byId(dto.id()).orElse(null);
        DbTargetConfig cfg;
        try {
            cfg = buildFromDto(withIdFallback(dto), prev);
        } catch (IllegalArgumentException e) {
            return TestResult.fail("invalid config: " + e.getMessage(), 0);
        }

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("hk-test-" + (cfg.id() == null ? "adhoc" : cfg.id()));
        hc.setJdbcUrl(cfg.jdbcUrl());
        hc.setUsername(cfg.username());
        hc.setPassword(cfg.password());
        hc.setMaximumPoolSize(1);
        hc.setMinimumIdle(0);
        hc.setConnectionTimeout(Math.max(1_000L, cfg.hikari().connectionTimeoutMs()));
        hc.setValidationTimeout(hc.getConnectionTimeout());
        hc.setReadOnly(true);
        hc.setAutoCommit(true);
        hc.setRegisterMbeans(false);
        applyNetworkTimeouts(hc, cfg);

        long startNs = System.nanoTime();
        try (HikariDataSource ds = new HikariDataSource(hc);
             Connection c = ds.getConnection()) {
            boolean valid = c.isValid(3);
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            if (!valid) return TestResult.fail("isValid() returned false", ms);
            return TestResult.ok(ms);
        } catch (Exception e) {
            long ms = (System.nanoTime() - startNs) / 1_000_000L;
            return TestResult.fail(rootMessage(e), ms);
        }
    }

    /* ================================================================== */

    private DbTargetConfig buildFromDto(TargetDto dto, DbTargetConfig prev) {
        String id = dto.id() != null ? dto.id() : (prev == null ? null : prev.id());
        if (id == null) throw new IllegalArgumentException("id is required");

        String displayName = nz(dto.displayName(), prev != null ? prev.name() : id);
        int displayOrder = dto.displayOrder() != null ? dto.displayOrder()
                : (prev != null ? prev.displayOrder() : 0);
        DbType engine = dto.engine() != null ? dto.engine()
                : (prev != null ? prev.type() : null);
        if (engine == null) throw new IllegalArgumentException("engine is required");
        String jdbc = nz(dto.jdbcUrl(), prev != null ? prev.jdbcUrl() : null);
        String user = nz(dto.username(), prev != null ? prev.username() : null);
        // Keep prior password on partial update when DTO omits the field.
        String password = dto.password() != null ? dto.password()
                : (prev != null ? prev.password() : "");
        long pollMs = dto.pollIntervalMs() != null ? dto.pollIntervalMs()
                : (prev != null ? prev.pollIntervalMs() : 1000L);
        String hostExporter = dto.hostExporterUrl() != null ? dto.hostExporterUrl()
                : (prev != null ? prev.hostExporterUrl() : "");

        String opsUser = dto.opsUsername() != null ? dto.opsUsername()
                : (prev != null && prev.ops() != null ? prev.ops().username() : "");
        String opsPwd = dto.opsPassword() != null ? dto.opsPassword()
                : (prev != null && prev.ops() != null ? prev.ops().password() : "");

        HikariSettings hikari = new HikariSettings(
                dto.hikariMaxPoolSize() != null ? dto.hikariMaxPoolSize()
                        : (prev != null ? prev.hikari().maximumPoolSize() : 4),
                dto.hikariMinimumIdle() != null ? dto.hikariMinimumIdle()
                        : (prev != null ? prev.hikari().minimumIdle() : 1),
                prev != null ? prev.hikari().connectionTimeoutMs()   : 3_000L,
                prev != null ? prev.hikari().idleTimeoutMs()         : 60_000L,
                prev != null ? prev.hikari().maxLifetimeMs()         : 600_000L,
                prev != null ? prev.hikari().validationTimeoutMs()   : 3_000L
        );
        NetworkSettings net = new NetworkSettings(
                dto.tcpConnectTimeoutMs() != null ? dto.tcpConnectTimeoutMs()
                        : (prev != null ? prev.network().tcpConnectTimeoutMs() : 5_000L),
                dto.socketReadTimeoutMs() != null ? dto.socketReadTimeoutMs()
                        : (prev != null ? prev.network().socketReadTimeoutMs() : 8_000L)
        );
        OpsSettings ops = new OpsSettings(opsUser == null ? "" : opsUser, opsPwd == null ? "" : opsPwd);

        if (jdbc == null || jdbc.isBlank()) throw new IllegalArgumentException("jdbcUrl is required");
        if (user == null || user.isBlank()) throw new IllegalArgumentException("username is required");

        return new DbTargetConfig(id, displayName, engine, jdbc, user, password,
                pollMs, displayOrder, hikari, net, hostExporter, ops);
    }

    /**
     * For test-connection without persisting, the client may omit id; we
     * fabricate a stable placeholder so validation passes.
     */
    private static TargetDto withIdFallback(TargetDto d) {
        if (d.id() != null && !d.id().isBlank()) return d;
        return new TargetDto("test-connection", d.displayName(), d.displayOrder(), d.engine(), d.jdbcUrl(),
                d.username(), d.password(), d.passwordSet(), d.pollIntervalMs(), d.enabled(),
                d.hostExporterUrl(), d.opsUsername(), d.opsPassword(), d.opsPasswordSet(),
                d.hikariMaxPoolSize(), d.hikariMinimumIdle(), d.tcpConnectTimeoutMs(), d.socketReadTimeoutMs());
    }

    private static void applyNetworkTimeouts(HikariConfig cfg, DbTargetConfig target) {
        long readMs    = target.network().socketReadTimeoutMs();
        long connectMs = target.network().tcpConnectTimeoutMs();
        switch (target.type()) {
            case ORACLE -> {
                cfg.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(connectMs));
                cfg.addDataSourceProperty("oracle.net.READ_TIMEOUT",    String.valueOf(readMs));
                cfg.addDataSourceProperty("oracle.jdbc.ReadTimeout",    String.valueOf(readMs));
            }
            case MARIADB, MYSQL -> {
                cfg.addDataSourceProperty("connectTimeout", String.valueOf(connectMs));
                cfg.addDataSourceProperty("socketTimeout",  String.valueOf(readMs));
                cfg.addDataSourceProperty("tcpKeepAlive",   "true");
            }
            case MSSQL -> {
                cfg.addDataSourceProperty("loginTimeout",   String.valueOf(Math.max(1L, connectMs / 1000L)));
                cfg.addDataSourceProperty("socketTimeout",  String.valueOf(readMs));
            }
        }
    }

    private static void validateId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (id.length() > ID_MAX) throw new IllegalArgumentException("id too long (>" + ID_MAX + ")");
        if (!id.matches("[a-z0-9][a-z0-9\\-_]*")) {
            throw new IllegalArgumentException(
                    "id must match [a-z0-9][a-z0-9-_]* — use a slug like 'trading-core-db'");
        }
    }

    private static void require(Object v, String name) {
        if (v == null || (v instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String nz(String candidate, String fallback) {
        return candidate != null && !candidate.isBlank() ? candidate : fallback;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return msg != null ? msg : cur.getClass().getSimpleName();
    }

    public record TestResult(boolean ok, String message, long latencyMs) {
        public static TestResult ok(long ms)               { return new TestResult(true, "connected", ms); }
        public static TestResult fail(String m, long ms)   { return new TestResult(false, m, ms); }
    }

    /** Convenience static used by the controller to adapt a {@code List<Map>} reorder payload. */
    public static List<TargetStore.OrderUpdate> toOrderUpdates(List<ReorderRequest.Entry> entries) {
        if (entries == null) return List.of();
        List<TargetStore.OrderUpdate> out = new ArrayList<>(entries.size());
        for (ReorderRequest.Entry e : entries) {
            out.add(new TargetStore.OrderUpdate(e.id(), e.displayOrder() == null ? 0 : e.displayOrder()));
        }
        return out;
    }

    public record ReorderRequest(List<Entry> entries) {
        public record Entry(String id, Integer displayOrder) {}
    }
}
