package com.deepgaze.collector.mysql;

import com.deepgaze.config.DeepGazeProperties;
import com.deepgaze.model.DbType;
import org.springframework.stereotype.Component;

@Component
public class MySqlCollector extends MysqlFamilyCollector {

    public MySqlCollector(DeepGazeProperties props) {
        super(props);
    }

    @Override
    public DbType supports() { return DbType.MYSQL; }
}
