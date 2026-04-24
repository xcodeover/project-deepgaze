package com.deepgaze.session.strategy;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.session.ExplainResult;

/**
 * Engine-specific execution of a pre-validated SELECT/DML statement against
 * the target DB's native EXPLAIN surface. Mirrors the {@code KillStrategy}
 * split under {@code com.deepgaze.ops.strategy}: one small class per engine
 * so each dialect's quirks (PLAN_TABLE cleanup, SHOWPLAN_XML session state)
 * stay local.
 *
 * The service {@code SessionDetailService.explain} does the engine-agnostic
 * gating (truncation, prepared-placeholder, explainability heuristics) before
 * calling here, so a strategy can assume the SQL is worth running.
 *
 * Output contract — {@link ExplainResult#plan} shape per engine:
 *   MARIADB → native {@code EXPLAIN FORMAT=JSON} tree
 *   ORACLE  → {@code {"format":"text","title":"...","lines":[...]}}
 *   MSSQL   → {@code {"format":"xml","title":"...","xml":"<ShowPlanXML ...>"}}
 *
 * The frontend switches on {@code plan.format} (or absence, for native JSON)
 * so a new engine only needs its strategy + one render branch.
 */
public interface ExplainStrategy {

    DbType supports();

    ExplainResult explain(DbTargetConfig target, String sql);
}
