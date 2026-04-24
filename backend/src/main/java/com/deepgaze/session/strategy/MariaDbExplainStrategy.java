package com.deepgaze.session.strategy;

import com.deepgaze.config.MyBatisFactoryRegistry;
import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.session.ExplainResult;
import com.deepgaze.session.SessionDetailMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@code EXPLAIN FORMAT=JSON <stmt>} via the existing MyBatis mapper. The
 * mapper {@code ${sql}} inline is deliberate — EXPLAIN's argument is syntax,
 * not a bindable value. The JDBC driver still parses exactly one statement
 * per call, so embedded semicolons become a syntax error rather than a
 * multi-statement escape.
 */
@Slf4j
@Component
public class MariaDbExplainStrategy implements ExplainStrategy {

    private final MyBatisFactoryRegistry factories;
    private final ObjectMapper json = new ObjectMapper();

    public MariaDbExplainStrategy(MyBatisFactoryRegistry factories) {
        this.factories = factories;
    }

    @Override
    public DbType supports() { return DbType.MARIADB; }

    @Override
    public ExplainResult explain(DbTargetConfig target, String sql) {
        SqlSessionFactory factory = factories.factoryFor(target.id()).orElse(null);
        if (factory == null) {
            return ExplainResult.failed("No MyBatis factory for target: " + target.id());
        }
        try (SqlSession session = factory.openSession(true)) {
            SessionDetailMapper m = session.getMapper(SessionDetailMapper.class);
            Map<String, Object> row = m.explainJson(sql);
            String planJson = firstStringValue(row);
            if (planJson == null) {
                return ExplainResult.rejected("Engine returned an empty EXPLAIN plan.");
            }
            JsonNode parsed = json.readTree(planJson);
            return ExplainResult.ok(parsed);
        } catch (JsonProcessingException e) {
            log.warn("MariaDB EXPLAIN returned unparseable JSON: target={} msg={}", target.id(), e.getMessage());
            return ExplainResult.rejected("Engine returned a plan that was not valid JSON.");
        } catch (Exception e) {
            String msg = rootMessage(e);
            log.warn("MariaDB EXPLAIN failed: target={} msg={}", target.id(), msg);
            return ExplainResult.failed(msg);
        }
    }

    private static String firstStringValue(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return null;
        Object v = row.values().iterator().next();
        return v == null ? null : v.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}
