package com.deepgaze.collector.mysql;

import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbType;
import org.springframework.stereotype.Component;

@Component
public class MySqlSlowCollector extends MysqlFamilySlowCollector {

    public MySqlSlowCollector(DeepGazeProperties props) {
        super(props);
    }

    @Override
    public DbType supports() { return DbType.MYSQL; }

    /** MySQL 8.0+ uses the unprefixed INNODB_TABLESPACES view name. */
    @Override
    protected String tablespacesView() { return "INNODB_TABLESPACES"; }
}
