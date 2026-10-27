package io.github.chdurai.agenticbanking.tools.port;

import java.util.Map;

public interface MetricsQueryPort extends ToolPort {

    record MetricsQuery(String service, String environment, int lookbackMinutes) {
    }

    record MetricsSnapshot(
            String service,
            String environment,
            int lookbackMinutes,
            long sampleCount,
            Map<String, Double> values) {
    }

    MetricsSnapshot query(MetricsQuery request);

    @Override
    default String id() {
        return "ops.metrics.query";
    }

    @Override
    default SideEffect sideEffect() {
        return SideEffect.NONE;
    }
}
