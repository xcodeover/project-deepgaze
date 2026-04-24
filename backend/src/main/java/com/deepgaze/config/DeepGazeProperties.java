package com.deepgaze.config;

import com.deepgaze.alert.AlertRule;
import com.deepgaze.model.DbTargetConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "deepgaze")
public record DeepGazeProperties(
        @DefaultValue Buffer buffer,
        @DefaultValue Scheduler scheduler,
        @DefaultValue Alerts alerts,
        @DefaultValue Ops ops,
        @DefaultValue History history,
        @DefaultValue StorageSaturation storageSaturation,
        @DefaultValue List<DbTargetConfig> targets,
        @DefaultValue List<BusinessMetric> businessMetrics
) {
    public record Buffer(@DefaultValue("10000") int capacity) {}

    /**
     * V9 Time Machine — embedded SQLite persistence for MetricSnapshot replay.
     * Defaults trade footprint for coverage: 48h retention, 200-row / 500ms
     * batches, 10k drop-oldest queue so bursty collectors never block the
     * reactive pipeline. Disable by setting enabled=false in application.yml.
     */
    public record History(
            @DefaultValue("true")                        boolean enabled,
            @DefaultValue("./data/deepgaze-history.db") String dbPath,
            @DefaultValue("48")                          int retentionHours,
            @DefaultValue("200")                         int batchSize,
            @DefaultValue("500")                         long batchIntervalMs,
            @DefaultValue("10000")                       int queueCapacity,
            @DefaultValue("60")                          long purgeIntervalMinutes
    ) {}

    /**
     * Active-response (Kill Session) config. `authToken` is the shared secret
     * clients must present as `X-Deepgaze-Auth`. Blank disables the endpoint
     * entirely — the controller stays mounted but every request returns 503,
     * so a leaked build can't quietly expose mutation capability.
     */
    public record Ops(
            @DefaultValue("") String authToken
    ) {
        public boolean isEnabled() {
            return authToken != null && !authToken.isBlank();
        }
    }

    public record Scheduler(
            @DefaultValue("16")    int workerPoolSize,
            @DefaultValue("5000")  long collectionTimeoutMs,
            // --- Slow queue (60s default) for expensive dictionary queries ---
            // like Oracle DBA_TABLESPACE_USAGE_METRICS or MSSQL
            // sys.dm_db_file_space_usage. Runs on a thread pool that is fully
            // isolated from the fast loop so a stall here cannot delay the
            // 1s infra-metric tick.
            @DefaultValue("60000") long slowIntervalMs,
            @DefaultValue("4")     int slowWorkerPoolSize,
            @DefaultValue("10000") long slowCollectionTimeoutMs
    ) {}

    /**
     * Threshold policy for the Storage Saturation tile. WARN/CRIT are applied
     * server-side so the classification is consistent across API consumers
     * (frontend, future webhooks, AI analysers) and the frontend just maps
     * the enum to a colour.
     */
    public record StorageSaturation(
            @DefaultValue("80.0") double warnPct,
            @DefaultValue("90.0") double critPct
    ) {}

    public record Alerts(
            @DefaultValue("30")  long defaultForSeconds,
            @DefaultValue("500") int  eventHistoryCapacity,
            @DefaultValue        List<AlertRule> rules,
            @DefaultValue        List<WebhookSinkConfig> webhooks
    ) {}

    /**
     * A single HTTP delivery target. `chatId` is optional — when set, the
     * sink emits Telegram's {chat_id,text} shape so the URL can point
     * straight at `https://api.telegram.org/bot<token>/sendMessage` with
     * no bridge in between. When absent, the sink sends a generic JSON
     * payload suitable for Slack / Discord / custom receivers.
     */
    public record WebhookSinkConfig(
            String id,
            String url,
            @DefaultValue("POST") String method,
            @DefaultValue("3000") long timeoutMs,
            String chatId
    ) {}

    /**
     * One config-driven KPI for the "Business Scoreboard" strip. The SQL is
     * executed on the target's existing HikariCP DataSource — no extra pool,
     * no MyBatis mapper — at an independent cadence (pollIntervalMs), so an
     * expensive business query cannot slow the 1s infra-metric tick.
     *
     * Fields:
     *   id            stable identifier (also used as the row key)
     *   target        exact targetId from deepgaze.targets
     *   label         human-readable name shown on the scoreboard card
     *   sql           statement that returns a single row, single column
     *                 (extra columns are ignored, NULL becomes 0)
     *   pollIntervalMs per-metric cadence — defaults to 10s because these
     *                 queries tend to be heavier than infra counters
     *   format        "number" | "currency" | "percent" | "integer" — drives
     *                 the frontend formatter; defaults to "number"
     */
    public record BusinessMetric(
            String id,
            String target,
            String label,
            String sql,
            @DefaultValue("10000") long pollIntervalMs,
            @DefaultValue("number") String format
    ) {}
}
