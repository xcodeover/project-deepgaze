package com.deepgaze.session.strategy;

import com.deepgaze.config.DataSourceRegistry;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.session.ExplainResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/**
 * Oracle EXPLAIN flow is a two-step dance:
 *   1. {@code EXPLAIN PLAN SET STATEMENT_ID = '...' FOR <stmt>} — populates
 *      PLAN_TABLE (a per-session GLOBAL TEMPORARY TABLE on 11g+) with the
 *      plan rows for this statement id.
 *   2. {@code SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE','<id>'))} —
 *      returns the plan as pre-formatted text rows, the exact shape a DBA
 *      expects to see in sqlplus.
 *
 * The unique statement id isolates concurrent explains (two operators clicking
 * at the same time). We best-effort delete PLAN_TABLE rows afterwards even
 * though the GTT is session-scoped — the Hikari pool hands the same physical
 * session back to later callers, so leftover rows could pollute other explains
 * in the same connection.
 */
@Slf4j
@Component
public class OracleExplainStrategy implements ExplainStrategy {

    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final DataSourceRegistry dataSources;
    private final ObjectMapper json = new ObjectMapper();

    public OracleExplainStrategy(DataSourceRegistry dataSources) {
        this.dataSources = dataSources;
    }

    @Override
    public DbType supports() { return DbType.ORACLE; }

    @Override
    public ExplainResult explain(DbTargetConfig target, String sql) {
        HikariDataSource ds = dataSources.dataSourceFor(target.id()).orElse(null);
        if (ds == null) {
            return ExplainResult.failed("No pool registered for target: " + target.id());
        }
        String statementId = "DG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        try (Connection c = ds.getConnection()) {
            try (Statement stmt = c.createStatement()) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + statementId + "' FOR " + sql);
            }

            ArrayNode lines = json.createArrayNode();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', ?, 'TYPICAL'))")) {
                ps.setString(1, statementId);
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String line = rs.getString(1);
                        lines.add(line == null ? "" : line);
                    }
                }
            }

            // Best-effort cleanup of this statement_id's rows. Leftovers in a
            // pooled session could confuse later explains that fall back to
            // "most recent" semantics if STATEMENT_ID filtering is relaxed.
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM PLAN_TABLE WHERE STATEMENT_ID = ?")) {
                ps.setString(1, statementId);
                ps.setQueryTimeout(5);
                ps.executeUpdate();
            } catch (Exception cleanup) {
                log.debug("Oracle PLAN_TABLE cleanup failed (non-fatal): {}", cleanup.getMessage());
            }

            if (lines.isEmpty()) {
                return ExplainResult.rejected("DBMS_XPLAN returned no rows for statement id " + statementId + ".");
            }
            ObjectNode plan = json.createObjectNode();
            plan.put("format", "text");
            plan.put("title", "EXPLAIN PLAN · DBMS_XPLAN.DISPLAY");
            plan.set("lines", lines);
            return ExplainResult.ok(plan);
        } catch (Exception e) {
            String msg = rootMessage(e);
            log.warn("Oracle EXPLAIN failed: target={} statementId={} msg={}", target.id(), statementId, msg);
            return ExplainResult.failed(msg);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}
