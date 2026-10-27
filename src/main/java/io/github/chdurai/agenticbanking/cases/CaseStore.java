package io.github.chdurai.agenticbanking.cases;

import java.util.List;
import java.util.Optional;

/**
 * System of record for cases. Implementations: in-memory (default demo) and JDBC (profile `jdbc`).
 * State change + outbox row are one atomic unit so the bus can never disagree with the case.
 */
public interface CaseStore {

    /** Result of {@link #claim}: the case, and whether this call created it. */
    record Claim(CaseRecord record, boolean created) {
    }

    /**
     * Atomically creates the case for (flow, idempotencyKey) or returns the existing one.
     *
     * @throws CaseStoreExceptions.IdempotencyConflictException when the key exists with a different requestHash
     */
    Claim claim(String flow, String idempotencyKey, String requestHash, String subjectRef);

    Claim enqueue(String flow, String idempotencyKey, String requestHash, String subjectRef, String requestJson);

    List<CaseRecord> queued(int limit);

    long countQueued();

    List<CaseRecord> running(int limit);

    List<CaseRecord> page(String afterId, int limit);

    CaseLink.Creation link(String fromCase, String toCase, CaseLink.Type type);

    Optional<CaseLink> findLink(String id);

    List<CaseLink> links(String caseId, String afterId, int limit);

    /**
     * Optimistic transition: succeeds only if the stored version equals expectedVersion.
     * Appends the case event and the outbox message in the same unit of work.
     */
    CaseRecord transition(String caseId, long expectedVersion, CaseStatus status, String resultJson,
                          String actor, String eventType, OutboxMessage outbox);

    Optional<CaseRecord> find(String caseId);

    List<CaseEvent> events(String caseId);

    List<OutboxMessage> pendingOutbox(int limit);

    void markPublished(long outboxId);

    void markFailed(long outboxId);

    long countCases();
}
