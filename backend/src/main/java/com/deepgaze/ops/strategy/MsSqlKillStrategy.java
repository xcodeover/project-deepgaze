package com.deepgaze.ops.strategy;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.ops.KillCommandService.SessionRow;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Set;

/**
 * SQL Server's KILL takes a bare SPID. We resolve the session through
 * {@code sys.dm_exec_sessions} (always has a row for connected SPIDs) and
 * enrich with {@code sys.dm_exec_requests} when the session has an active
 * request. {@code is_user_process = 1} at the SELECT level excludes system
 * SPIDs (historically &lt; 50) so they can never be materialised here.
 */
@Component
public class MsSqlKillStrategy implements KillStrategy {

    private static final Set<String> PROTECTED_USERS = Set.of("sa");

    @Override public DbType supports() { return DbType.MSSQL; }

    @Override
    public SessionRow lookup(Connection c, long threadId, int queryTimeoutSec) throws Exception {
        String sql =
                "SELECT TOP 1 s.session_id AS id, " +
                "       s.login_name AS user_name, " +
                "       s.host_name AS host, " +
                "       DB_NAME(COALESCE(r.database_id, s.database_id)) AS db, " +
                "       r.command AS command_name, " +
                "       DATEDIFF(SECOND, COALESCE(r.start_time, s.last_request_start_time), GETDATE()) AS time_secs, " +
                "       COALESCE(r.wait_type, s.status) AS state, " +
                "       SUBSTRING(t.text, 1, 200) AS info " +
                "  FROM sys.dm_exec_sessions s " +
                "  LEFT JOIN sys.dm_exec_requests r ON r.session_id = s.session_id " +
                "  OUTER APPLY sys.dm_exec_sql_text(r.sql_handle) t " +
                " WHERE s.session_id = ? AND s.is_user_process = 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSec);
            ps.setLong(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SessionRow(
                        rs.getLong("id"), 0L,
                        rs.getString("user_name"),
                        rs.getString("host"),
                        rs.getString("db"),
                        rs.getString("command_name"),
                        rs.getLong("time_secs"),
                        rs.getString("state"),
                        rs.getString("info"));
            }
        }
    }

    @Override
    public String preflight(SessionRow row, DbTargetConfig target) {
        if (row.user() == null) return "Session has no login_name — refusing to kill";
        if (PROTECTED_USERS.contains(row.user().toLowerCase()))
            return "Session belongs to " + row.user() + " — refusing to kill";
        if (equalsIgnoreCase(row.user(), target.username()))
            return "Session belongs to the monitoring user (" + row.user() + ") — refusing to kill";
        if (target.hasOps() && equalsIgnoreCase(row.user(), target.ops().username()))
            return "Session belongs to the ops user (" + row.user() + ") — refusing to kill";
        return null;
    }

    @Override
    public void kill(Connection c, SessionRow row, int queryTimeoutSec) throws Exception {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(queryTimeoutSec);
            st.execute("KILL " + row.id());
        }
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
