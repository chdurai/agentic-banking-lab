package io.github.chdurai.agenticbanking.incident;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Config for alert webhook ingestion, queue worker, and sliding-window correlation. */
@ConfigurationProperties("platform.incident.ingestion")
public record AlertIngestionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") int maxQueueSize,
        @DefaultValue("20") int maxBatchPerRun,
        @DefaultValue("10") int correlationWindowMinutes,
        @DefaultValue("5000") long workerIntervalMs
) {
    public AlertIngestionProperties {
        if (maxQueueSize < 1 || maxBatchPerRun < 1 || correlationWindowMinutes < 1 || workerIntervalMs < 1) {
            throw new IllegalArgumentException("Alert ingestion capacity, batch, window and interval must be positive");
        }
    }
}
