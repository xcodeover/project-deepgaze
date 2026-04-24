package com.deepgaze.session.strategy;

import com.deepgaze.config.DataSourceRegistry;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.session.ExplainResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * {@code SET SHOWPLAN_XML ON} flips a connection-level flag so the next
 * statement does not execute — it returns a one-row result set whose single
 * column is the full execution plan as XML. We must always turn the flag
 * back OFF before releasing the connection, otherwise the next Hikari
 * borrower's first statement would silently return a plan instead of doing
 * what they asked.
 *
 * Requires {@code SHOWPLAN} permission on the database (often granted via
 * {@code VIEW SERVER STATE} at the server level). If missing, the driver
 * raises a clear error that bubbles up as a typed {@code FAILED} result.
 */
@Slf4j
@Component
public class MsSqlExplainStrategy implements ExplainStrategy {

    private static final int QUERY_TIMEOUT_SECONDS = 10;

    private final DataSourceRegistry dataSources;
    private final ObjectMapper json = new ObjectMapper();

    public MsSqlExplainStrategy(DataSourceRegistry dataSources) {
        this.dataSources = dataSources;
    }

    @Override
    public DbType supports() { return DbType.MSSQL; }

    @Override
    public ExplainResult explain(DbTargetConfig target, String sql) {
        HikariDataSource ds = dataSources.dataSourceFor(target.id()).orElse(null);
        if (ds == null) {
            return ExplainResult.failed("No pool registered for target: " + target.id());
        }
        try (Connection c = ds.getConnection();
             Statement stmt = c.createStatement()) {
            stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            stmt.execute("SET SHOWPLAN_XML ON");
            try {
                StringBuilder xmlBuf = new StringBuilder();
                try (ResultSet rs = stmt.executeQuery(sql)) {
                    while (rs.next()) {
                        String chunk = rs.getString(1);
                        if (chunk != null) xmlBuf.append(chunk);
                    }
                }
                if (xmlBuf.isEmpty()) {
                    return ExplainResult.rejected("SET SHOWPLAN_XML returned no rows.");
                }
                ObjectNode plan = json.createObjectNode();
                plan.put("format", "xml");
                plan.put("title", "SET SHOWPLAN_XML");
                plan.put("xml", xmlBuf.toString());
                return ExplainResult.ok(plan);
            } finally {
                try {
                    stmt.execute("SET SHOWPLAN_XML OFF");
                } catch (Exception offErr) {
                    // Non-fatal but must be loud — a connection returned to the
                    // pool with SHOWPLAN still on breaks every subsequent caller
                    // until the connection is recycled.
                    log.warn("CRITICAL: failed to SET SHOWPLAN_XML OFF on target {} — connection may be poisoned: {}",
                            target.id(), offErr.getMessage());
                }
            }
        } catch (Exception e) {
            String msg = rootMessage(e);
            log.warn("MSSQL EXPLAIN failed: target={} msg={}", target.id(), msg);
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
