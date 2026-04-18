package com.deepgaze.config;

import com.deepgaze.model.DbTargetConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public final class HikariPoolFactory {

    private HikariPoolFactory() {}

    public static HikariDataSource build(DbTargetConfig target) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("hk-" + target.id());
        cfg.setJdbcUrl(target.jdbcUrl());
        cfg.setUsername(target.username());
        cfg.setPassword(target.password());

        DbTargetConfig.HikariSettings h = target.hikari();
        cfg.setMaximumPoolSize(h.maximumPoolSize());
        cfg.setMinimumIdle(h.minimumIdle());
        cfg.setConnectionTimeout(h.connectionTimeoutMs());   // wait for a connection from the POOL
        cfg.setIdleTimeout(h.idleTimeoutMs());
        cfg.setMaxLifetime(h.maxLifetimeMs());
        cfg.setValidationTimeout(h.validationTimeoutMs());

        cfg.setAutoCommit(true);
        cfg.setReadOnly(true);                               // monitoring is read-only
        cfg.setRegisterMbeans(true);

        applyNetworkTimeouts(cfg, target);

        return new HikariDataSource(cfg);
    }

    /**
     * Driver-level network timeouts are mandatory: Hikari's connectionTimeout
     * only governs pool-wait time, and Statement.setQueryTimeout requires the
     * server to respond. A blackholed socket bypasses both.
     */
    private static void applyNetworkTimeouts(HikariConfig cfg, DbTargetConfig target) {
        DbTargetConfig.NetworkSettings n = target.network();
        long readMs    = n.socketReadTimeoutMs();
        long connectMs = n.tcpConnectTimeoutMs();

        switch (target.type()) {
            case ORACLE -> {
                // ojdbc11 honours both spellings — set both for cross-version safety.
                cfg.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", String.valueOf(connectMs));
                cfg.addDataSourceProperty("oracle.net.READ_TIMEOUT",    String.valueOf(readMs));
                cfg.addDataSourceProperty("oracle.jdbc.ReadTimeout",    String.valueOf(readMs));
            }
            case MARIADB, MYSQL -> {
                // Both drivers accept these property names; values are millis.
                cfg.addDataSourceProperty("connectTimeout", String.valueOf(connectMs));
                cfg.addDataSourceProperty("socketTimeout",  String.valueOf(readMs));
                cfg.addDataSourceProperty("tcpKeepAlive",   "true");
            }
            case MSSQL -> {
                // mssql-jdbc: loginTimeout is SECONDS, socketTimeout is MILLIS.
                cfg.addDataSourceProperty("loginTimeout",   String.valueOf(Math.max(1L, connectMs / 1000L)));
                cfg.addDataSourceProperty("socketTimeout",  String.valueOf(readMs));
            }
        }
    }
}
