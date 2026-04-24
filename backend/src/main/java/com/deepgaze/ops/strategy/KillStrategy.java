package com.deepgaze.ops.strategy;

import com.deepgaze.model.DbTargetConfig;
import com.deepgaze.model.DbType;
import com.deepgaze.ops.KillCommandService.SessionRow;

import java.sql.Connection;

/**
 * Engine-specific steps that turn a UI-supplied {@code threadId} into a
 * validated KILL. Kept as three small classes rather than a switch in
 * KillCommandService so each engine's safety rules, lookup SQL, and kill
 * statement live together and can be reasoned about in isolation.
 *
 * The contract:
 *   1. {@link #lookup} returns null if the session no longer exists, otherwise
 *      a fully-populated SessionRow. Must filter out non-user / system sessions
 *      at the SQL level so they can never appear here.
 *   2. {@link #preflight} returns null if safe to kill, otherwise a human-
 *      readable reason used as the 409 rejection body.
 *   3. {@link #kill} issues the engine's KILL statement. No return value — a
 *      throw means failure, which KillCommandService translates to 500.
 */
public interface KillStrategy {

    DbType supports();

    SessionRow lookup(Connection c, long threadId, int queryTimeoutSec) throws Exception;

    String preflight(SessionRow row, DbTargetConfig target);

    void kill(Connection c, SessionRow row, int queryTimeoutSec) throws Exception;
}
