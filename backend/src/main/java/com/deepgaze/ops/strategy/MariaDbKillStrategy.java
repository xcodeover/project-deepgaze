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

@Component
public class MariaDbKillStrategy implements KillStrategy {

    private static final Set<String> PROTECTED_COMMANDS = Set.of("Binlog Dump", "Binlog Dump GTID");
    private static final Set<String> PROTECTED_USERS    = Set.of("system user", "event_scheduler");

    @Override public DbType supports() { return DbType.MARIADB; }

    @Override
    public SessionRow lookup(Connection c, long threadId, int queryTimeoutSec) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ID, USER, HOST, DB, COMMAND, TIME, STATE, LEFT(INFO, 200) AS INFO " +
                "FROM information_schema.processlist WHERE ID = ?")) {
            ps.setQueryTimeout(queryTimeoutSec);
            ps.setLong(1, threadId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SessionRow(
                        rs.getLong("ID"), 0L,
                        rs.getString("USER"), rs.getString("HOST"),
                        rs.getString("DB"), rs.getString("COMMAND"),
                        rs.getLong("TIME"), rs.getString("STATE"), rs.getString("INFO"));
            }
        }
    }

    @Override
    public String preflight(SessionRow row, DbTargetConfig target) {
        if (row.user() == null) return "Session has no USER — refusing to kill";
        if (PROTECTED_USERS.contains(row.user().toLowerCase()))
            return "Session belongs to " + row.user() + " — refusing to kill";
        if (equalsIgnoreCase(row.user(), target.username()))
            return "Session belongs to the monitoring user (" + row.user() + ") — refusing to kill";
        if (target.hasOps() && equalsIgnoreCase(row.user(), target.ops().username()))
            return "Session belongs to the ops user (" + row.user() + ") — refusing to kill";
        if (row.command() != null && PROTECTED_COMMANDS.contains(row.command()))
            return "Session is a replication stream (" + row.command() + ") — refusing to kill";
        return null;
    }

    @Override
    public void kill(Connection c, SessionRow row, int queryTimeoutSec) throws Exception {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout(queryTimeoutSec);
            // KILL takes an integer literal. The id was just round-tripped
            // through a parameterised SELECT, so there's no injection surface.
            st.execute("KILL " + row.id());
        }
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
