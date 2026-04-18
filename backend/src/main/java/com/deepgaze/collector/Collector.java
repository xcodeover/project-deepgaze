package com.deepgaze.collector;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.model.MetricSnapshot;

import javax.sql.DataSource;
import java.util.List;

/**
 * Strategy interface for per-DB-engine metric collection.
 *
 * Implementations MUST:
 *   - Use only lightweight, dictionary/in-memory queries (no heavy joins).
 *   - Call Statement#setQueryTimeout(...) on every JDBC statement so a hung target
 *     does not pin a worker thread past the scheduler's collection-timeout-ms.
 *   - Return one MetricSnapshot per logical metric family (sessions, sysstat, topSql, ...).
 */
public interface Collector {

    DbType supports();

    List<MetricSnapshot> collect(DbTargetConfig target, DataSource dataSource) throws Exception;
}
