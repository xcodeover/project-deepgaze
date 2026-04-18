package com.deepgaze;

import com.deepgaze.config.DeepGazeProperties;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * DataSourceAutoConfiguration excluded — we manage N HikariCP pools manually.
 * MybatisAutoConfiguration excluded — we build one SqlSessionFactory PER TARGET
 * in MyBatisFactoryRegistry instead of the single-DataSource model the starter
 * assumes.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        MybatisAutoConfiguration.class
})
@EnableConfigurationProperties(DeepGazeProperties.class)
public class DeepGazeApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeepGazeApplication.class, args);
    }
}
