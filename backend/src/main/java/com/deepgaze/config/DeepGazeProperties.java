package com.deepgaze.config;

import com.deepgaze.model.DbTargetConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties(prefix = "deepgaze")
public record DeepGazeProperties(
        @DefaultValue Buffer buffer,
        @DefaultValue Scheduler scheduler,
        @DefaultValue List<DbTargetConfig> targets
) {
    public record Buffer(@DefaultValue("10000") int capacity) {}

    public record Scheduler(
            @DefaultValue("16")   int workerPoolSize,
            @DefaultValue("5000") long collectionTimeoutMs
    ) {}
}
