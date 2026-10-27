package io.github.chdurai.agenticbanking.cases;

import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes committed outbox rows. Delivery is at-least-once and strictly after commit: a publish failure
 * only increments attempts and is retried; it can never alter the case or the decision already returned.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final CaseStore store;
    private final PlatformEventPublisher publisher;

    public OutboxRelay(CaseStore store, PlatformEventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    public int relayPending() {
        int published = 0;
        for (OutboxMessage m : store.pendingOutbox(100)) {
            try {
                publisher.publish(PlatformEvent.of(m.type(), m.flow(), m.caseId(),
                        "case " + m.caseId() + " subject " + m.subjectRef()));
                store.markPublished(m.id());
                published++;
            } catch (RuntimeException e) {
                store.markFailed(m.id());
                log.warn("[outbox] publish of {} failed (attempt {}): {}", m.id(), m.attempts() + 1, e.getMessage());
            }
        }
        return published;
    }
}
