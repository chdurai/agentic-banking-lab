package io.github.chdurai.agenticbanking.controller;

import jakarta.annotation.PreDestroy;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecRegistry;
import io.github.chdurai.agenticbanking.ops.AgentActivityEvent;
import io.github.chdurai.agenticbanking.ops.AgentActivityHub;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Live agent-observability feed for the Mission Control UI.
 *
 * <ul>
 *   <li>{@code GET /api/ops/observability/stream} — SSE: backlog replay then live events, 15s heartbeat</li>
 *   <li>{@code GET /api/ops/observability/recent} — JSON backlog snapshot</li>
 *   <li>{@code GET /api/ops/observability/topology} — declared agents + read-edges for the constellation view</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ops/observability")
public class ObservabilityController {

    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    public record TopologyNode(String id, String flow, String mode, boolean planned, boolean required,
                               int challengeable) {}

    public record TopologyEdge(String from, String to) {}

    public record Topology(List<TopologyNode> nodes, List<TopologyEdge> edges, String modelMode) {}

    private final AgentActivityHub hub;
    private final AgentSpecRegistry specs;
    @org.springframework.beans.factory.annotation.Value("${platform.loan.investigation.enabled:false}")
    private boolean investigationEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "observability-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public ObservabilityController(AgentActivityHub hub, AgentSpecRegistry specs) {
        this.hub = hub;
        this.specs = specs;
    }

    @GetMapping("/recent")
    public List<AgentActivityEvent> recent(@RequestParam(defaultValue = "100") int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        return hub.recent(bounded);
    }

    @GetMapping("/topology")
    public Topology topology() {
        List<TopologyNode> nodes = new java.util.ArrayList<>(specs.all().stream()
                .map(s -> new TopologyNode(s.agentId(), s.flow(), s.mode().name(), s.planned(),
                        s.required(), s.challengeable()))
                .toList());
        List<TopologyEdge> edges = new java.util.ArrayList<>(specs.all().stream()
                .flatMap(s -> s.reads().stream().map(r -> new TopologyEdge(r, s.agentId())))
                .distinct()
                .toList());
        if (investigationEnabled) {
            nodes.add(new TopologyNode("loan-investigator", "loan", "LLM", false, false, 0));
            for (String predecessor : List.of("intake", "kyc", "credit-risk", "compliance")) {
                edges.add(new TopologyEdge(predecessor, "loan-investigator"));
            }
            edges.add(new TopologyEdge("loan-investigator", "decision"));
        }
        return new Topology(nodes, edges, environment != null && io.github.chdurai.agenticbanking.showcase.ShowcaseConfiguration.isLocal(environment)
                ? "SCRIPTED" : "CONFIGURED_PROVIDER");
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @org.springframework.web.bind.annotation.RequestHeader(value = "Last-Event-ID", required = false)
            Long lastEventId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> heartbeat =
                new java.util.concurrent.atomic.AtomicReference<>();

        // Exactly-once, ordered replay (resuming after Last-Event-ID) + live events, all delivered
        // on the hub's single dispatch thread.
        Runnable unsubscribe = hub.subscribeWithReplay(event -> send(emitter, event),
                lastEventId == null ? 0 : lastEventId, 200);
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            unsubscribe.run();
            var beat = heartbeat.get();
            if (beat != null) {
                beat.cancel(false);
            }
            emitter.complete();
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        heartbeat.set(heartbeats.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                cleanup.run();
            }
        }, 15, 15, TimeUnit.SECONDS));
        if (closed.get()) {
            var beat = heartbeat.get();
            if (beat != null) {
                beat.cancel(false);
            }
        }
        return emitter;
    }

    private boolean send(SseEmitter emitter, AgentActivityEvent event) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(event.seq())).name("activity").data(event));
            return true;
        } catch (IOException | IllegalStateException e) {
            emitter.complete();
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
    }
}
