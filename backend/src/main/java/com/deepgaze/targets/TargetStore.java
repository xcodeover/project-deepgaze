package com.deepgaze.targets;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Control-plane SQLite store for DB target definitions. Intentionally
 * separate from the V9 history DB (different retention policy, different
 * access pattern — targets are small + tx-heavy, history is append-only
 * batch-writes). One physical file at
 * {@code deepgaze.target-store.db-path}, opened in WAL mode.
 *
 * Plain JDBC rather than MyBatis: the existing {@link com.deepgaze.config.MyBatisFactoryRegistry}
 * builds one SqlSessionFactory PER target, which assumes the target list is
 * already known — a chicken/egg with storing targets in SQLite. Mirrors the
 * pattern already established by {@link com.deepgaze.history.HistoryStore}.
 *
 * Schema v1 (DDL below) covers everything currently bound in
 * {@link com.deepgaze.model.DbTargetConfig} so a DB-loaded target is
 * behaviourally identical to a yaml-bound one.
 */
@Slf4j
@Component
public class TargetStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS target (
                id                       TEXT    PRIMARY KEY,
                display_name             TEXT    NOT NULL,
                display_order            INTEGER NOT NULL DEFAULT 0,
                engine                   TEXT    NOT NULL,
                jdbc_url                 TEXT    NOT NULL,
                username                 TEXT    NOT NULL,
                password_enc             TEXT    NOT NULL,
                poll_interval_ms         INTEGER NOT NULL DEFAULT 1000,
                enabled                  INTEGER NOT NULL DEFAULT 1,
                host_exporter_url        TEXT    NOT NULL DEFAULT '',
                ops_username             TEXT    NOT NULL DEFAULT '',
                ops_password_enc         TEXT    NOT NULL DEFAULT '',
                hikari_max_pool_size     INTEGER NOT NULL DEFAULT 4,
                hikari_minimum_idle      INTEGER NOT NULL DEFAULT 1,
                tcp_connect_timeout_ms   INTEGER NOT NULL DEFAULT 5000,
                socket_read_timeout_ms   INTEGER NOT NULL DEFAULT 8000,
                created_at               INTEGER NOT NULL,
                updated_at               INTEGER NOT NULL
            )
            """;

    private static final String DDL_IDX_ORDER = """
            CREATE INDEX IF NOT EXISTS idx_target_order ON target(display_order, id)
            """;

    private static final String SELECT_ALL = """
            SELECT id, display_name, display_order, engine, jdbc_url, username, password_enc,
                   poll_interval_ms, enabled, host_exporter_url, ops_username, ops_password_enc,
                   hikari_max_pool_size, hikari_minimum_idle,
                   tcp_connect_timeout_ms, socket_read_timeout_ms,
                   created_at, updated_at
              FROM target
             ORDER BY display_order ASC, id ASC
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, display_name, display_order, engine, jdbc_url, username, password_enc,
                   poll_interval_ms, enabled, host_exporter_url, ops_username, ops_password_enc,
                   hikari_max_pool_size, hikari_minimum_idle,
                   tcp_connect_timeout_ms, socket_read_timeout_ms,
                   created_at, updated_at
              FROM target
             WHERE id = ?
            """;

    private static final String INSERT = """
            INSERT INTO target (
                id, display_name, display_order, engine, jdbc_url, username, password_enc,
                poll_interval_ms, enabled, host_exporter_url, ops_username, ops_password_enc,
                hikari_max_pool_size, hikari_minimum_idle,
                tcp_connect_timeout_ms, socket_read_timeout_ms,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPDATE = """
            UPDATE target
               SET display_name = ?, display_order = ?, engine = ?, jdbc_url = ?, username = ?,
                   password_enc = ?, poll_interval_ms = ?, enabled = ?, host_exporter_url = ?,
                   ops_username = ?, ops_password_enc = ?,
                   hikari_max_pool_size = ?, hikari_minimum_idle = ?,
                   tcp_connect_timeout_ms = ?, socket_read_timeout_ms = ?,
                   updated_at = ?
             WHERE id = ?
            """;

    private static final String DELETE = "DELETE FROM target WHERE id = ?";

    private static final String UPDATE_ORDER = "UPDATE target SET display_order = ?, updated_at = ? WHERE id = ?";

    private static final String UPDATE_ENABLED = "UPDATE target SET enabled = ?, updated_at = ? WHERE id = ?";

    private static final String COUNT = "SELECT COUNT(*) FROM target";

    private final String dbPath;
    private Connection conn;

    public TargetStore(@Value("${deepgaze.target-store.db-path:./data/deepgaze-targets.db}") String dbPath) {
        this.dbPath = dbPath;
    }

    @PostConstruct
    public synchronized void init() throws SQLException {
        File f = new File(dbPath);
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("Could not create targets parent dir: {}", parent);
        }
        String url = "jdbc:sqlite:" + f.getAbsolutePath();
        this.conn = DriverManager.getConnection(url);
        // Pragmas + DDL in autocommit, then switch to manual-commit for CRUD.
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute(DDL);
            s.execute(DDL_IDX_ORDER);
        }
        this.conn.setAutoCommit(false);
        log.info("TargetStore ready: path={}", f.getAbsolutePath());
    }

    public synchronized int count() {
        try (PreparedStatement ps = conn.prepareStatement(COUNT);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("TargetStore.count failed: {}", e.getMessage());
            return 0;
        }
    }

    public synchronized List<TargetRow> findAll() {
        List<TargetRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapRow(rs));
        } catch (SQLException e) {
            log.warn("TargetStore.findAll failed: {}", e.getMessage());
        }
        return out;
    }

    public synchronized TargetRow findById(String id) {
        if (id == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            log.warn("TargetStore.findById failed: {}", e.getMessage());
            return null;
        }
    }

    public synchronized void insert(TargetRow r) {
        try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
            int i = 1;
            ps.setString(i++, r.id());
            ps.setString(i++, r.displayName());
            ps.setInt   (i++, r.displayOrder());
            ps.setString(i++, r.engine());
            ps.setString(i++, r.jdbcUrl());
            ps.setString(i++, r.username());
            ps.setString(i++, r.passwordEnc());
            ps.setLong  (i++, r.pollIntervalMs());
            ps.setInt   (i++, r.enabled() ? 1 : 0);
            ps.setString(i++, r.hostExporterUrl() == null ? "" : r.hostExporterUrl());
            ps.setString(i++, r.opsUsername() == null ? "" : r.opsUsername());
            ps.setString(i++, r.opsPasswordEnc() == null ? "" : r.opsPasswordEnc());
            ps.setInt   (i++, r.hikariMaxPoolSize());
            ps.setInt   (i++, r.hikariMinimumIdle());
            ps.setLong  (i++, r.tcpConnectTimeoutMs());
            ps.setLong  (i++, r.socketReadTimeoutMs());
            ps.setLong  (i++, r.createdAt());
            ps.setLong  (i,   r.updatedAt());
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("TargetStore.insert failed for id=" + r.id() + ": " + e.getMessage(), e);
        }
    }

    public synchronized boolean update(TargetRow r) {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE)) {
            int i = 1;
            ps.setString(i++, r.displayName());
            ps.setInt   (i++, r.displayOrder());
            ps.setString(i++, r.engine());
            ps.setString(i++, r.jdbcUrl());
            ps.setString(i++, r.username());
            ps.setString(i++, r.passwordEnc());
            ps.setLong  (i++, r.pollIntervalMs());
            ps.setInt   (i++, r.enabled() ? 1 : 0);
            ps.setString(i++, r.hostExporterUrl() == null ? "" : r.hostExporterUrl());
            ps.setString(i++, r.opsUsername() == null ? "" : r.opsUsername());
            ps.setString(i++, r.opsPasswordEnc() == null ? "" : r.opsPasswordEnc());
            ps.setInt   (i++, r.hikariMaxPoolSize());
            ps.setInt   (i++, r.hikariMinimumIdle());
            ps.setLong  (i++, r.tcpConnectTimeoutMs());
            ps.setLong  (i++, r.socketReadTimeoutMs());
            ps.setLong  (i++, r.updatedAt());
            ps.setString(i,   r.id());
            int n = ps.executeUpdate();
            conn.commit();
            return n > 0;
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("TargetStore.update failed for id=" + r.id() + ": " + e.getMessage(), e);
        }
    }

    public synchronized boolean delete(String id) {
        try (PreparedStatement ps = conn.prepareStatement(DELETE)) {
            ps.setString(1, id);
            int n = ps.executeUpdate();
            conn.commit();
            return n > 0;
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("TargetStore.delete failed for id=" + id + ": " + e.getMessage(), e);
        }
    }

    public synchronized boolean updateEnabled(String id, boolean enabled) {
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_ENABLED)) {
            ps.setInt   (1, enabled ? 1 : 0);
            ps.setLong  (2, System.currentTimeMillis());
            ps.setString(3, id);
            int n = ps.executeUpdate();
            conn.commit();
            return n > 0;
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("TargetStore.updateEnabled failed for id=" + id + ": " + e.getMessage(), e);
        }
    }

    /** Bulk reorder; all updates commit in a single transaction. */
    public synchronized void reorder(List<OrderUpdate> updates) {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(UPDATE_ORDER)) {
            for (OrderUpdate u : updates) {
                ps.setInt   (1, u.displayOrder());
                ps.setLong  (2, now);
                ps.setString(3, u.id());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("TargetStore.reorder failed: " + e.getMessage(), e);
        }
    }

    private void rollback() {
        try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
    }

    private static TargetRow mapRow(ResultSet rs) throws SQLException {
        return new TargetRow(
                rs.getString("id"),
                rs.getString("display_name"),
                rs.getInt("display_order"),
                rs.getString("engine"),
                rs.getString("jdbc_url"),
                rs.getString("username"),
                rs.getString("password_enc"),
                rs.getLong("poll_interval_ms"),
                rs.getInt("enabled") == 1,
                rs.getString("host_exporter_url"),
                rs.getString("ops_username"),
                rs.getString("ops_password_enc"),
                rs.getInt("hikari_max_pool_size"),
                rs.getInt("hikari_minimum_idle"),
                rs.getLong("tcp_connect_timeout_ms"),
                rs.getLong("socket_read_timeout_ms"),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    @PreDestroy
    public synchronized void close() {
        if (conn == null) return;
        try { conn.close(); } catch (SQLException e) { log.warn("TargetStore close error: {}", e.getMessage()); }
        conn = null;
    }

    public record OrderUpdate(String id, int displayOrder) {}
}
