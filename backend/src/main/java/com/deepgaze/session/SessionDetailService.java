package com.deepgaze.session;

import com.deepgaze.config.MyBatisFactoryRegistry;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.session.strategy.ExplainStrategy;
import com.deepgaze.targets.TargetRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * On-demand session detail + EXPLAIN. Runs on a boundedElastic thread via the
 * controller's Mono.fromCallable wrapper, so blocking JDBC / MyBatis I/O
 * never touches a reactor event loop.
 *
 * Engine-agnostic gating lives here (truncation, prepared-placeholder, and
 * explainability heuristics) — each is universal enough that all three
 * engines benefit. Actual plan execution is delegated to a per-engine
 * {@link ExplainStrategy} so MariaDB's JSON tree, Oracle's DBMS_XPLAN text,
 * and MSSQL's SHOWPLAN XML each keep their dialect quirks in a file of their
 * own.
 */
@Slf4j
@Service
public class SessionDetailService {

    private final MyBatisFactoryRegistry factories;
    private final TargetRegistry targets;
    private final Map<DbType, ExplainStrategy> explainStrategies;

    public SessionDetailService(MyBatisFactoryRegistry factories,
                                TargetRegistry targets,
                                List<ExplainStrategy> explainBeans) {
        this.factories = factories;
        this.targets = targets;
        this.explainStrategies = new EnumMap<>(DbType.class);
        for (ExplainStrategy s : explainBeans) {
            explainStrategies.put(s.supports(), s);
        }
    }

    public SessionDetail fetchDetail(String targetId, long pid) {
        DbTargetConfig target = findTarget(targetId).orElseThrow(() ->
                new IllegalArgumentException("Unknown target: " + targetId));
        if (target.type() != DbType.MARIADB) {
            throw new UnsupportedOperationException(
                    "Session detail not yet implemented for " + target.type());
        }
        SqlSessionFactory factory = factories.factoryFor(targetId).orElseThrow(() ->
                new IllegalStateException("No MyBatis factory for target: " + targetId));

        try (SqlSession session = factory.openSession(true)) {
            SessionDetailMapper m = session.getMapper(SessionDetailMapper.class);
            Map<String, Object> row = m.selectSessionById(pid);
            return SessionDetail.from(pid, row);
        }
    }

    /**
     * Validates the SQL and dispatches to the engine's EXPLAIN strategy.
     * Returns a typed ExplainResult for every expected outcome — the
     * controllers always return 200 with this body so the frontend renders
     * the reason inline rather than via a toast.
     */
    public ExplainResult explain(String targetId, String sql) {
        DbTargetConfig target = findTarget(targetId).orElseThrow(() ->
                new IllegalArgumentException("Unknown target: " + targetId));

        if (sql == null || sql.isBlank()) {
            return ExplainResult.rejected("No SQL text available for this session.");
        }
        String trimmed = stripTrailingSemicolons(sql.trim());
        if (trimmed.isEmpty()) {
            return ExplainResult.rejected("SQL is empty after trimming terminators.");
        }
        if (looksTruncated(trimmed)) {
            return ExplainResult.rejected(
                    "SQL appears truncated (PROCESSLIST.INFO / v$sql text is capped). " +
                    "Cannot EXPLAIN a partial statement.");
        }
        if (looksLikePrepared(trimmed)) {
            return ExplainResult.rejected(
                    "Prepared-statement placeholders (?, :name) cannot be EXPLAINed " +
                    "without bound values — replace them with literals.");
        }
        if (!looksExplainable(trimmed)) {
            return ExplainResult.rejected(
                    "Only SELECT / INSERT / UPDATE / DELETE / REPLACE / WITH can be EXPLAINed.");
        }

        ExplainStrategy strategy = explainStrategies.get(target.type());
        if (strategy == null) {
            return ExplainResult.unsupported(
                    "EXPLAIN not yet implemented for " + target.type());
        }
        return strategy.explain(target, trimmed);
    }

    private Optional<DbTargetConfig> findTarget(String id) {
        return targets.byId(id);
    }

    /**
     * Oracle JDBC rejects trailing {@code ;} as a syntax error; MariaDB and
     * MSSQL tolerate it. Strip once, universally, so operator copy-paste from
     * sqlplus / ssms works across engines.
     */
    private static String stripTrailingSemicolons(String sql) {
        String s = sql;
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    // PROCESSLIST.INFO / v$sql SQL_TEXT are both capped. The symptom we care
    // about is simpler: no terminating keyword / clause where one would be
    // expected. A trailing incomplete identifier is the most common giveaway.
    private static boolean looksTruncated(String sql) {
        if (sql.length() < 8) return true;
        if (endsWithUnbalancedQuote(sql)) return true;
        return sql.endsWith(",") || sql.endsWith("(") || sql.endsWith(".");
    }

    private static boolean endsWithUnbalancedQuote(String sql) {
        int single = 0, dbl = 0;
        boolean escaped = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '\'') single++;
            else if (c == '"') dbl++;
        }
        return (single % 2 != 0) || (dbl % 2 != 0);
    }

    private static boolean looksLikePrepared(String sql) {
        // MariaDB / Oracle placeholders: ? positional, :name named. We cannot
        // bind values here, so either form means the plan would be vacuous.
        return sql.contains("?") || sql.matches("(?s).*\\W:[a-zA-Z_][a-zA-Z0-9_]*.*");
    }

    private static boolean looksExplainable(String sql) {
        String head = sql.toUpperCase();
        return head.startsWith("SELECT")
                || head.startsWith("INSERT")
                || head.startsWith("UPDATE")
                || head.startsWith("DELETE")
                || head.startsWith("REPLACE")
                || head.startsWith("WITH")
                || head.startsWith("MERGE");
    }
}
