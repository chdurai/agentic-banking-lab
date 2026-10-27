package io.github.chdurai.agenticbanking.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-process consumer of platform events: audit history + metrics.
 * Target architecture: replace/augment with a Kafka producer (Spring Cloud Stream)
 * feeding notification, analytics and audit services.
 */
@Component
public class PlatformEventListener {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventListener.class);

    private final BoundedHistory<PlatformEvent> history = new BoundedHistory<>(500);
    private final AgentMetrics metrics;

    public PlatformEventListener(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void on(PlatformEvent event) {
        history.add(event);
        metrics.recordCounter("platform.events", "type", event.type());
        log.info("[event] {} {} {}", event.type(), event.flow(), event.subjectId());
    }

    public List<PlatformEvent> recent(int limit) {
        return history.recent(limit);
    }
}
