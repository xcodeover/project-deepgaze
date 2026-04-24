package com.deepgaze.session;

import java.util.Map;

/**
 * Typed envelope for the Session Detail drill-down. `found=false` means the
 * session has disappeared between the processlist snapshot and the click —
 * the frontend renders this as an explicit "session no longer active"
 * message rather than an empty drawer.
 *
 * Fields are a flat subset of the PROCESSLIST + INNODB_TRX +
 * performance_schema.events_waits_current join. Null-friendly because many
 * columns are conditional: trx_* rows only exist when the session is inside
 * a transaction, wait_* rows only when actively waiting.
 */
public record SessionDetail(
        boolean found,
        long id,
        String user,
        String host,
        String db,
        String command,
        Long timeSecs,
        String state,
        String info,
        String trxId,
        String trxState,
        String trxStarted,
        Long trxRowsLocked,
        Long trxRowsModified,
        String trxIsolation,
        String waitEvent,
        Long waitTimer,
        String waitObjectSchema,
        String waitObjectName
) {
    public static SessionDetail notFound(long pid) {
        return new SessionDetail(
                false, pid,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null);
    }

    public static SessionDetail from(long pid, Map<String, Object> row) {
        if (row == null || row.isEmpty()) return notFound(pid);
        return new SessionDetail(
                true,
                pid,
                asString(row.get("user")),
                asString(row.get("host")),
                asString(row.get("db")),
                asString(row.get("command")),
                asLong(row.get("time_secs")),
                asString(row.get("state")),
                asString(row.get("info")),
                asString(row.get("trx_id")),
                asString(row.get("trx_state")),
                asString(row.get("trx_started")),
                asLong(row.get("trx_rows_locked")),
                asLong(row.get("trx_rows_modified")),
                asString(row.get("trx_isolation")),
                asString(row.get("wait_event")),
                asLong(row.get("wait_timer")),
                asString(row.get("wait_object_schema")),
                asString(row.get("wait_object_name"))
        );
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long asLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
