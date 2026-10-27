package io.github.chdurai.agenticbanking.grievance;

import java.time.Instant;

/** Final closure metadata for a grievance, captured from officer/HITL decisions. */
public record GrievanceClosure(
        String grievanceId,
        String projectId,
        GrievanceFinalClassification classification,
        String decidedBy,
        String note,
        Instant closedAt,
        String approvalId
) {
}
