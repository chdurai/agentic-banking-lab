package io.github.chdurai.agenticbanking.grievance;

import java.time.Instant;
import java.util.List;

/**
 * Outcome of grievance handling. For confidential cases the description is never stored
 * or echoed: only the id, route, SLA and generic handling instructions.
 */
public record GrievanceResolution(
        String grievanceId,
        String projectId,
        Status status,
        Route route,
        int slaHours,
        GrievanceTriage triage,
        GrievanceResponseDraft responseDraft,
        List<String> flags,
        String narrative,
        String approvalId,
        Instant handledAt
) {
    public enum Status {
        ROUTED,                  // routed with a drafted response; low-risk
        PENDING_HUMAN_REVIEW,    // high-risk: reviewer must approve before anything is sent
        CONFIDENTIAL_ESCALATION, // sensitive case, LLM bypassed, restricted handling
        BLOCKED                  // input guardrails
    }

    public enum Route {
        PROJECT_GRM_OFFICER,
        SAFEGUARDS_SPECIALIST,
        CONFIDENTIAL_CASE_TEAM,
        NONE
    }
}
