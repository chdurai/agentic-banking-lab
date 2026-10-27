package io.github.chdurai.agenticbanking.cases;

import java.time.Instant;

/** A business event waiting to be relayed to the message bus. Written in the same transaction as the state change. */
public record OutboxMessage(long id, String caseId, String type, String flow, String subjectRef,
                            String payloadJson, int attempts, Instant createdAt) {
}
