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
 * Oracle requires both SID and SERIAL# to identify a session uniquely across
 * SID reuse — the pair is what {@code ALTER SYSTEM KILL SESSION 'sid,serial#'}
 * binds to. We look up SERIAL# here and carry it on the SessionRow so the
 * kill step can re-assemble the literal without a second round-trip.
 *
 * TYPE='USER' at the SELECT level excludes background/internal sessions so
 * they can never be materialised into a SessionRow, let alone killed.
 */
@Component
public class OracleKillStrategy implements KillStrategy {

    /** Oracle stores usernames uppercase; compare case-insensitively. */
    private static final Set<String> PROTECTED_USERS = Set.of("SYS", "SYSTEM");

    @Override public DbType supports() { return DbType.ORACLE; }

    @Override
    public SessionRow lookup(Connection c, long threadId, int queryTimeoutSec) throws Exception {
        String sql =
                "SELECT s.SID              AS id, " +
                "       s.SERIAL#          AS serial_no, " +
                "       s.USERNAME         AS user_name, " +
                "       NVL(s.MACHINE, '') AS host, " +
                "       s.SCHEMANAME       AS db, " +
                "       NVL(s.PROGRAM, '') AS command_name, " +
                "       NVL(s.LAST_CALL_ET, 0) AS time_secs, " +
                "       s.STATUS           AS state, " +
                "       SUBSTR(q.SQL_TEXT, 1, 200) AS info " +
                "  FROM v$session s " +
                "  LEFT JOIN v$sql q " +
                "    ON q.SQL_ID = s.SQL_ID AND q.CHILD_NUMBER = s.SQL_CHILD_NUMBER " +
                " WHERE s.SID = ? AND s.TYPE = 'USER'";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(queryTimeoutSec);
            ps.setLong(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SessionRow(
                        rs.getLong("id"),
                        rs.getLong("serial_no"),
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
        if (row.user() == null) return "Session has no USERNAME — refusing to kill";
        if (PROTECTED_USERS.contains(row.user().toUpperCase()))
            return "Session belongs to " + row.user() + " — refusing to kill";
        if (equalsIgnoreCase(row.user(), target.username()))
            return "Session belongs to the monitoring user (" + row.user() + ") — refusing to kill";
        if (target.hasOps() && equalsIgnoreCase(row.user(), target.ops().username()))
            return "Session belongs to the ops user (" + row.user() + ") — refusing to kill";
        return null;
    }

    @Override
    public void kill(Connection c, SessionRow row, int queryTimeoutSec) throws Exception {
        // sid and serial are server-supplied longs from our own SELECT — no
        // injection surface. IMMEDIATE rolls back the open transaction and
        // releases locks synchronously instead of waiting for PMON to reap.
        String stmt = "ALTER SYSTEM KILL SESSION '" + row.id() + "," + row.serial() + "' IMMEDIATE";
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(queryTimeoutSec);
            st.execute(stmt);
        }
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
