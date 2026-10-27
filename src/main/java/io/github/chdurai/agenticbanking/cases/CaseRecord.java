package io.github.chdurai.agenticbanking.cases;

import java.time.Instant;

/** Durable aggregate for one unit of work (loan evaluation, incident, grievance...). */
public record CaseRecord(
        String caseId,
        String flow,
        /** Pseudonymous reference; the mapping to a real identity lives outside this table. */
        String subjectRef,
        String idempotencyKey,
        String requestHash,
        CaseStatus status,
        long version,
        String resultJson,
        Instant createdAt,
        Instant updatedAt,
        String tenantId,
        String requestJson) {
    public CaseRecord(String caseId, String flow, String subjectRef, String idempotencyKey,
                      String requestHash, CaseStatus status, long version, String resultJson,
                      Instant createdAt, Instant updatedAt) {
        this(caseId, flow, subjectRef, idempotencyKey, requestHash, status, version, resultJson,
                createdAt, updatedAt, io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId(), null);
    }
}
