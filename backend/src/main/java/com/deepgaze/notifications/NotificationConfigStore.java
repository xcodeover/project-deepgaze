package com.deepgaze.notifications;

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
 * Control-plane SQLite store for the single-row notification config.
 * Parallels {@link com.deepgaze.targets.TargetStore} — separate file, same
 * pragmas (WAL, NORMAL sync), plain JDBC. The table always holds exactly one
 * row at id=1 (we UPSERT rather than upsert-per-field) so
 * {@link #load()} and {@link #save(NotificationConfig)} are the entire API.
 *
 * The bot token column stores the AES-GCM ciphertext produced by
 * {@link com.deepgaze.targets.PasswordCipher} — the store itself is
 * crypto-agnostic.
 */
@Slf4j
@Component
public class NotificationConfigStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS notification_config (
                id                   INTEGER PRIMARY KEY CHECK (id = 1),
                enabled              INTEGER NOT NULL DEFAULT 0,
                notify_on_critical   INTEGER NOT NULL DEFAULT 1,
                notify_on_warning    INTEGER NOT NULL DEFAULT 1,
                telegram_bot_token   TEXT    NOT NULL DEFAULT '',
                telegram_chat_id     TEXT    NOT NULL DEFAULT '',
                updated_at           INTEGER NOT NULL
            )
            """;

    private static final String SELECT = """
            SELECT enabled, notify_on_critical, notify_on_warning,
                   telegram_bot_token, telegram_chat_id, updated_at
              FROM notification_config WHERE id = 1
            """;

    private static final String UPSERT = """
            INSERT INTO notification_config (
                id, enabled, notify_on_critical, notify_on_warning,
                telegram_bot_token, telegram_chat_id, updated_at
            ) VALUES (1, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                enabled            = excluded.enabled,
                notify_on_critical = excluded.notify_on_critical,
                notify_on_warning  = excluded.notify_on_warning,
                telegram_bot_token = excluded.telegram_bot_token,
                telegram_chat_id   = excluded.telegram_chat_id,
                updated_at         = excluded.updated_at
            """;

    private final String dbPath;
    private Connection conn;

    public NotificationConfigStore(
            @Value("${deepgaze.notification-store.db-path:./data/deepgaze-notifications.db}") String dbPath
    ) {
        this.dbPath = dbPath;
    }

    @PostConstruct
    public synchronized void init() throws SQLException {
        File f = new File(dbPath);
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("Could not create notification-store parent dir: {}", parent);
        }
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute(DDL);
        }
        this.conn.setAutoCommit(false);
        log.info("NotificationConfigStore ready: path={}", f.getAbsolutePath());
    }

    /** Returns the single row, or {@link NotificationConfig#defaults()} if the table is empty. */
    public synchronized NotificationConfig load() {
        try (PreparedStatement ps = conn.prepareStatement(SELECT);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return NotificationConfig.defaults();
            return new NotificationConfig(
                    rs.getInt("enabled") == 1,
                    rs.getInt("notify_on_critical") == 1,
                    rs.getInt("notify_on_warning") == 1,
                    rs.getString("telegram_bot_token"),
                    rs.getString("telegram_chat_id"),
                    rs.getLong("updated_at")
            );
        } catch (SQLException e) {
            log.warn("NotificationConfigStore.load failed: {}", e.getMessage());
            return NotificationConfig.defaults();
        }
    }

    /** Idempotent upsert of the single row. */
    public synchronized void save(NotificationConfig cfg) {
        try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
            ps.setInt   (1, cfg.enabled() ? 1 : 0);
            ps.setInt   (2, cfg.notifyOnCritical() ? 1 : 0);
            ps.setInt   (3, cfg.notifyOnWarning() ? 1 : 0);
            ps.setString(4, cfg.telegramBotToken() == null ? "" : cfg.telegramBotToken());
            ps.setString(5, cfg.telegramChatId()   == null ? "" : cfg.telegramChatId());
            ps.setLong  (6, cfg.updatedAt());
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("NotificationConfigStore.save failed: " + e.getMessage(), e);
        }
    }

    private void rollback() {
        try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
    }

    @PreDestroy
    public synchronized void close() {
        if (conn == null) return;
        try { conn.close(); } catch (SQLException e) {
            log.warn("NotificationConfigStore close error: {}", e.getMessage());
        }
        conn = null;
    }
}
