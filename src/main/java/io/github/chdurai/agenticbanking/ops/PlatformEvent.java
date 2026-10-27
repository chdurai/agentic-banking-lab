package io.github.chdurai.agenticbanking.ops;

import java.time.Instant;

/**
 * A significant business event emitted by a workflow (loan decided, incident resolved,
 * grievance routed, document processed, approval decided).
 *
 * This is the microservices seam: today the listener records events in-process;
 * in the target architecture the same events are published to Kafka topics by swapping
 * the listener for a stream binder, with no change to the orchestrators that emit them.
 */
public record PlatformEvent(
        String type,
        String flow,
        String subjectId,
        String summary,
        Instant at
) {
    public static PlatformEvent of(String type, String flow, String subjectId, String summary) {
        return new PlatformEvent(type, flow, subjectId, summary, Instant.now());
    }
}
