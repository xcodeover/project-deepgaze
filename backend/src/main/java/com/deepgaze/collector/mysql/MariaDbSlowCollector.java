package com.deepgaze.collector.mysql;

import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbType;
import org.springframework.stereotype.Component;

@Component
public class MariaDbSlowCollector extends MysqlFamilySlowCollector {

    public MariaDbSlowCollector(DeepGazeProperties props) {
        super(props);
    }

    @Override
    public DbType supports() { return DbType.MARIADB; }

    /**
     * MariaDB 10.0-10.5 only exposes INNODB_SYS_TABLESPACES; 10.6+ has both
     * names as aliases. Using the SYS-prefixed name works on every MariaDB
     * version we target.
     */
    @Override
    protected String tablespacesView() { return "INNODB_SYS_TABLESPACES"; }
}
