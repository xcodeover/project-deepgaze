package com.deepgaze.history;

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

/**
 * SQLite store for the single-row {@link HistoryConfig}. Parallels
 * {@link com.deepgaze.notifications.NotificationConfigStore} — separate file,
 * WAL mode, one UPSERT path. Lives in its own db file (not the big history
 * data file) so a retention change cannot contend with the writer loop.
 */
@Slf4j
@Component
public class HistoryConfigStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS history_config (
                id              INTEGER PRIMARY KEY CHECK (id = 1),
                retention_hours INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL
            )
            """;

    private static final String SELECT = """
            SELECT retention_hours, updated_at FROM history_config WHERE id = 1
            """;

    private static final String UPSERT = """
            INSERT INTO history_config (id, retention_hours, updated_at)
                 VALUES (1, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                retention_hours = excluded.retention_hours,
                updated_at      = excluded.updated_at
            """;

    private final String dbPath;
    private Connection conn;

    public HistoryConfigStore(
            @Value("${deepgaze.app-settings-store.db-path:./data/deepgaze-app-settings.db}") String dbPath
    ) {
        this.dbPath = dbPath;
    }

    @PostConstruct
    public synchronized void init() throws SQLException {
        File f = new File(dbPath);
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("Could not create app-settings parent dir: {}", parent);
        }
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute(DDL);
        }
        this.conn.setAutoCommit(false);
        log.info("HistoryConfigStore ready: path={}", f.getAbsolutePath());
    }

    /** Returns the single row, or {@code null} if no row has been written yet. */
    public synchronized HistoryConfig load() {
        try (PreparedStatement ps = conn.prepareStatement(SELECT);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            return new HistoryConfig(
                    rs.getInt("retention_hours"),
                    rs.getLong("updated_at")
            );
        } catch (SQLException e) {
            log.warn("HistoryConfigStore.load failed: {}", e.getMessage());
            return null;
        }
    }

    public synchronized void save(HistoryConfig cfg) {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setInt (1, cfg.retentionHours());
            ps.setLong(2, cfg.updatedAt());
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            try { conn.rollback(); } catch (SQLException ignored) {}
            throw new IllegalStateException("HistoryConfigStore.save failed: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public synchronized void close() {
        if (conn == null) return;
        try { conn.close(); } catch (SQLException e) {
            log.warn("HistoryConfigStore close error: {}", e.getMessage());
        }
        conn = null;
    }
}
