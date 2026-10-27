package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.ExecutionTraceStore;
import io.github.chdurai.agenticbanking.ops.ModelMonitor;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AIOps endpoints: agent telemetry summary and recent execution traces.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final AgentMetrics metrics;
    private final ExecutionTraceStore traces;
    private final ModelMonitor monitor;
    private final PlatformEventListener events;
    private final LlmGateway gateway;

    public OpsController(AgentMetrics metrics, ExecutionTraceStore traces, ModelMonitor monitor,
                         PlatformEventListener events, LlmGateway gateway) {
        this.gateway = gateway;
        this.events = events;
        this.monitor = monitor;
        this.metrics = metrics;
        this.traces = traces;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.summary();
    }

    /** LLM gateway state: kill switch, breaker states per provider:model, bulkhead, routes. */
    @GetMapping("/llm")
    public Map<String, Object> llm() {
        return gateway.snapshot();
    }

    /** Operator kill switch: {@code on=true} stops every model call; flows degrade to humans. */
    @PostMapping("/llm/kill-switch")
    public Map<String, Object> killSwitch(@RequestParam boolean on) {
        gateway.setKillSwitch(on);
        return Map.of("killSwitch", gateway.killSwitchOn());
    }

    @GetMapping("/cost")
    public Map<String, Object> cost() {
        return metrics.costReport();
    }

    /** Decision mix, human-review rate, drift of recent decisions vs baseline, HITL outcomes. */
    @GetMapping("/monitoring")
    public Map<String, Object> monitoring(@RequestParam(defaultValue = "50") int window) {
        return monitor.report(window);
    }

    /** Platform event stream (audit seam; maps to Kafka topics in the target architecture). */
    @GetMapping("/events")
    public List<PlatformEvent> events(@RequestParam(defaultValue = "50") int limit) {
        return events.recent(limit);
    }

    @GetMapping("/traces")
    public List<ExecutionTraceStore.TraceEntry> traces(
            @RequestParam(defaultValue = "50") int limit) {
        return traces.recent(limit);
    }
}
