package io.github.chdurai.agenticbanking.approval;

import java.time.Instant;
import java.util.List;

/**
 * A pending (or decided) human-in-the-loop checkpoint. The human decision (APPROVED/REJECTED/OVERRIDDEN)
 * and the outcome of executing the approved action (executionLog, or EXECUTION_FAILED) are separate facts.
 */
public record ApprovalRequest(
        String id,
        String flow,
        String subjectId,
        String title,
        List<String> details,
        ApprovalEvidencePacket evidencePacket,
        Status status,
        Instant createdAt,
        Instant decidedAt,
        String decidedBy,
        String reasonCode,
        String note,
        List<String> executionLog,
        String requestedBy,
        String requiredRole,
        Instant expiresAt,
        Instant escalatedAt,
        boolean blindFirstRequired,
        BlindFirstAssessment blindFirstAssessment,
        boolean reasonCodeRequired,
        ActionSpec action,
        long version
) {
    public enum Status { PENDING, APPROVED, REJECTED, OVERRIDDEN, EXPIRED, EXECUTION_FAILED }

    ApprovalRequest decided(Status newStatus, String by, String code, String comment, Instant at) {
        return new ApprovalRequest(id, flow, subjectId, title, details, evidencePacket,
                newStatus, createdAt, at, by, code, comment, executionLog, requestedBy, requiredRole,
                expiresAt, escalatedAt, blindFirstRequired, blindFirstAssessment, reasonCodeRequired, action,
                version + 1);
    }

    ApprovalRequest withExecution(Status newStatus, List<String> log) {
        return new ApprovalRequest(id, flow, subjectId, title, details, evidencePacket,
                newStatus, createdAt, decidedAt, decidedBy, reasonCode, note, log, requestedBy, requiredRole,
                expiresAt, escalatedAt, blindFirstRequired, blindFirstAssessment, reasonCodeRequired, action,
                version + 1);
    }

    ApprovalRequest escalated(Instant at) {
        return new ApprovalRequest(id, flow, subjectId, title, details, evidencePacket,
                status, createdAt, decidedAt, decidedBy, reasonCode, note, executionLog, requestedBy, requiredRole,
                expiresAt, at, blindFirstRequired, blindFirstAssessment, reasonCodeRequired, action, version + 1);
    }

    ApprovalRequest withBlindFirst(BlindFirstAssessment assessment) {
        return new ApprovalRequest(id, flow, subjectId, title, details, evidencePacket,
                status, createdAt, decidedAt, decidedBy, reasonCode, note, executionLog, requestedBy, requiredRole,
                expiresAt, escalatedAt, blindFirstRequired, assessment, reasonCodeRequired, action, version + 1);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isOpen() {
        return status == Status.PENDING;
    }
}
