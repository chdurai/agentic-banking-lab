package io.github.chdurai.agenticbanking.ops;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory ring buffer of recent agent execution traces (last 200).
 * Production: ship to OpenTelemetry / Azure Monitor instead.
 */
@Component
public class ExecutionTraceStore {

    private static final int CAPACITY = 200;

    private final ConcurrentLinkedDeque<TraceEntry> traces = new ConcurrentLinkedDeque<>();

    public record TraceEntry(
            Instant at,
            String flow,        // loan | incident
            String correlationId,
            String agent,
            long durationMs,
            String outcome
    ) {}

    public void record(String flow, String correlationId, String agent,
                       long durationMs, String outcome) {
        traces.addLast(new TraceEntry(Instant.now(), flow, correlationId, agent, durationMs, outcome));
        while (traces.size() > CAPACITY) {
            traces.pollFirst();
        }
    }

    /** Most-recent-first. */
    public List<TraceEntry> recent(int limit) {
        List<TraceEntry> out = new java.util.ArrayList<>();
        var it = traces.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }
}
