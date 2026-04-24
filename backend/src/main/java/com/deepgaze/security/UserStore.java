package com.deepgaze.security;

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
 * Control-plane SQLite store for user accounts. Parallels
 * {@link com.deepgaze.targets.TargetStore} — separate file, WAL mode, plain
 * JDBC so no MyBatis SqlSessionFactory-per-target indirection applies.
 *
 * Schema v1: username is the natural key (lowercased on the way in), the
 * password column holds a BCrypt hash (never plaintext), and {@code role}
 * is free-form so adding new roles doesn't require a migration.
 */
@Slf4j
@Component
public class UserStore {

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS users (
                id             INTEGER PRIMARY KEY AUTOINCREMENT,
                username       TEXT    NOT NULL UNIQUE,
                password_hash  TEXT    NOT NULL,
                role           TEXT    NOT NULL DEFAULT 'ADMIN',
                enabled        INTEGER NOT NULL DEFAULT 1,
                created_at     INTEGER NOT NULL,
                updated_at     INTEGER NOT NULL
            )
            """;

    private static final String SELECT_BY_USERNAME = """
            SELECT id, username, password_hash, role, enabled, created_at, updated_at
              FROM users WHERE username = ?
            """;

    private static final String INSERT = """
            INSERT INTO users (username, password_hash, role, enabled, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, username, password_hash, role, enabled, created_at, updated_at
              FROM users WHERE id = ?
            """;

    private static final String SELECT_ALL = """
            SELECT id, username, password_hash, role, enabled, created_at, updated_at
              FROM users ORDER BY username
            """;

    private static final String UPDATE_PASSWORD = """
            UPDATE users SET password_hash = ?, updated_at = ? WHERE id = ?
            """;

    private static final String UPDATE_ROLE = """
            UPDATE users SET role = ?, updated_at = ? WHERE id = ?
            """;

    private static final String UPDATE_ENABLED = """
            UPDATE users SET enabled = ?, updated_at = ? WHERE id = ?
            """;

    private static final String DELETE_BY_ID = "DELETE FROM users WHERE id = ?";

    private static final String COUNT = "SELECT COUNT(*) FROM users";

    private static final String COUNT_ENABLED_ADMINS =
            "SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND enabled = 1";

    private final String dbPath;
    private Connection conn;

    public UserStore(@Value("${deepgaze.user-store.db-path:./data/deepgaze-users.db}") String dbPath) {
        this.dbPath = dbPath;
    }

    @PostConstruct
    public synchronized void init() throws SQLException {
        File f = new File(dbPath);
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.warn("Could not create user-store parent dir: {}", parent);
        }
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + f.getAbsolutePath());
        try (Statement s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute(DDL);
        }
        this.conn.setAutoCommit(false);
        log.info("UserStore ready: path={}", f.getAbsolutePath());
    }

    public synchronized int count() {
        try (PreparedStatement ps = conn.prepareStatement(COUNT);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("UserStore.count failed: {}", e.getMessage());
            return 0;
        }
    }

    public synchronized int countEnabledAdmins() {
        try (PreparedStatement ps = conn.prepareStatement(COUNT_ENABLED_ADMINS);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.warn("UserStore.countEnabledAdmins failed: {}", e.getMessage());
            return 0;
        }
    }

    public synchronized List<UserRow> findAll() {
        List<UserRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(SELECT_ALL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(mapRow(rs));
        } catch (SQLException e) {
            log.warn("UserStore.findAll failed: {}", e.getMessage());
        }
        return out;
    }

    public synchronized UserRow findById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_ID)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            log.warn("UserStore.findById failed: {}", e.getMessage());
            return null;
        }
    }

    public synchronized UserRow findByUsername(String username) {
        if (username == null) return null;
        try (PreparedStatement ps = conn.prepareStatement(SELECT_BY_USERNAME)) {
            ps.setString(1, username.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        } catch (SQLException e) {
            log.warn("UserStore.findByUsername failed: {}", e.getMessage());
            return null;
        }
    }

    public synchronized void insert(String username, String passwordHash, String role) {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
            ps.setString(1, username.toLowerCase());
            ps.setString(2, passwordHash);
            ps.setString(3, role);
            ps.setInt   (4, 1);
            ps.setLong  (5, now);
            ps.setLong  (6, now);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("UserStore.insert failed for username=" + username + ": " + e.getMessage(), e);
        }
    }

    public synchronized void updatePassword(long id, String passwordHash) {
        runUpdate(UPDATE_PASSWORD, ps -> {
            ps.setString(1, passwordHash);
            ps.setLong  (2, System.currentTimeMillis());
            ps.setLong  (3, id);
        }, "updatePassword");
    }

    public synchronized void updateRole(long id, String role) {
        runUpdate(UPDATE_ROLE, ps -> {
            ps.setString(1, role);
            ps.setLong  (2, System.currentTimeMillis());
            ps.setLong  (3, id);
        }, "updateRole");
    }

    public synchronized void updateEnabled(long id, boolean enabled) {
        runUpdate(UPDATE_ENABLED, ps -> {
            ps.setInt (1, enabled ? 1 : 0);
            ps.setLong(2, System.currentTimeMillis());
            ps.setLong(3, id);
        }, "updateEnabled");
    }

    public synchronized boolean deleteById(long id) {
        try (PreparedStatement ps = conn.prepareStatement(DELETE_BY_ID)) {
            ps.setLong(1, id);
            int n = ps.executeUpdate();
            conn.commit();
            return n > 0;
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("UserStore.deleteById failed for id=" + id + ": " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface PsBinder { void bind(PreparedStatement ps) throws SQLException; }

    private void runUpdate(String sql, PsBinder binder, String op) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            rollback();
            throw new IllegalStateException("UserStore." + op + " failed: " + e.getMessage(), e);
        }
    }

    private void rollback() {
        try { if (conn != null) conn.rollback(); } catch (SQLException ignored) {}
    }

    private static UserRow mapRow(ResultSet rs) throws SQLException {
        return new UserRow(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("role"),
                rs.getInt("enabled") == 1,
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    @PreDestroy
    public synchronized void close() {
        if (conn == null) return;
        try { conn.close(); } catch (SQLException e) {
            log.warn("UserStore close error: {}", e.getMessage());
        }
        conn = null;
    }
}
