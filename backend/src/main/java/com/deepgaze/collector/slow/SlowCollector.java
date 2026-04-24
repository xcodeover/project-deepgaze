package com.deepgaze.collector.slow;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.model.StorageSaturationDto;

import javax.sql.DataSource;

/**
 * Strategy interface for the 60s slow queue — per-engine storage saturation.
 *
 * Implementations MUST:
 *   - Call Statement#setQueryTimeout with the slow-queue timeout so a stalled
 *     dictionary view (e.g. a blocked DBA_TABLESPACE_USAGE_METRICS refresh on
 *     an unhealthy Oracle instance) doesn't pin a slow worker thread past
 *     the budget.
 *   - Return one StorageSaturationDto containing all engine-relevant items.
 *   - Be stateless — the scheduler reuses a single instance across targets.
 */
public interface SlowCollector {

    DbType supports();

    StorageSaturationDto collectSaturation(DbTargetConfig target, DataSource dataSource) throws Exception;
}
