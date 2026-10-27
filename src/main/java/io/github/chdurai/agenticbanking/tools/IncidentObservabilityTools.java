package io.github.chdurai.agenticbanking.tools;

import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.ExecutionTraceStore;
import io.github.chdurai.agenticbanking.tools.port.MetricsQueryPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Read-only incident diagnostics tools for bounded tool-use mode.
 */
@Component
public class IncidentObservabilityTools implements MetricsQueryPort {

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;
    private final ExecutionTraceStore traces;
    private final AgentMetrics metrics;
    private final ToolCallAuditor auditor;

    public IncidentObservabilityTools(io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities,
                                      ExecutionTraceStore traces,
                                      AgentMetrics metrics,
                                      ToolCallAuditor auditor) {
        this.capabilities = capabilities;
        this.traces = traces;
        this.metrics = metrics;
        this.auditor = auditor;
    }

    @Tool(description = "Read-only metrics lookup for incident diagnostics (recent error-rate/latency indicators).")
    public MetricsSnapshot queryMetrics(String service, String environment, int lookbackMinutes) {
        return query(new MetricsQuery(service, environment, Math.max(1, lookbackMinutes)));
    }

    @Override
    public MetricsSnapshot query(MetricsQuery request) {
        capabilities.checkTool("ops.metrics.query");
        return auditor.audited("ops.metrics.query", request, () -> {
            List<ExecutionTraceStore.TraceEntry> recent = traces.recent(200).stream()
                    .filter(t -> "incident".equals(t.flow()))
                    .toList();
            long errors = recent.stream().filter(t -> t.outcome().startsWith("ERROR")).count();
            double errRate = recent.isEmpty() ? 0.0 : (errors * 100.0 / recent.size());
            double p95 = percentile95(recent.stream().mapToLong(ExecutionTraceStore.TraceEntry::durationMs).toArray());
            return new MetricsSnapshot(
                    request.service(),
                    request.environment(),
                    request.lookbackMinutes(),
                    recent.size(),
                    Map.of(
                            "incidentErrorRatePct", round(errRate),
                            "incidentP95LatencyMs", p95,
                            "knownAgentCostUsd", extractCost(metrics.costReport())));
        });
    }

    private static double percentile95(long[] sortedValues) {
        if (sortedValues.length == 0) {
            return 0;
        }
        java.util.Arrays.sort(sortedValues);
        int idx = (int) Math.ceil(0.95 * sortedValues.length) - 1;
        idx = Math.max(0, Math.min(sortedValues.length - 1, idx));
        return sortedValues[idx];
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double extractCost(Map<String, Object> report) {
        Object total = report.get("totalEstimatedUsd");
        return total instanceof Number n ? n.doubleValue() : 0.0;
    }
}
