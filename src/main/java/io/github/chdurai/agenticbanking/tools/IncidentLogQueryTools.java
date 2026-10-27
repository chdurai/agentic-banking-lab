package io.github.chdurai.agenticbanking.tools;

import io.github.chdurai.agenticbanking.ops.ExecutionTraceStore;
import io.github.chdurai.agenticbanking.tools.port.LogQueryPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Read-only incident trace lookup for bounded tool-use mode.
 */
@Component
public class IncidentLogQueryTools implements LogQueryPort {

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;
    private final ExecutionTraceStore traces;
    private final ToolCallAuditor auditor;

    public IncidentLogQueryTools(io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities,
                                 ExecutionTraceStore traces,
                                 ToolCallAuditor auditor) {
        this.capabilities = capabilities;
        this.traces = traces;
        this.auditor = auditor;
    }

    @Tool(description = "Read-only recent trace lines for incident diagnostics.")
    public LogSnapshot queryLogs(String service, String environment, int limit) {
        return query(new LogQuery(service, environment, limit));
    }

    @Override
    public LogSnapshot query(LogQuery request) {
        capabilities.checkTool("ops.logs.query");
        int cap = Math.max(1, Math.min(20, request.limit()));
        return auditor.audited("ops.logs.query", request, () -> {
            List<LogLine> lines = traces.recent(cap).stream()
                    .filter(t -> "incident".equals(t.flow()))
                    .map(t -> new LogLine(t.at(), t.agent(),
                            "durationMs=" + t.durationMs() + " outcome=" + t.outcome()))
                    .toList();
            return new LogSnapshot(request.service(), request.environment(), lines.size(), lines);
        });
    }
}
