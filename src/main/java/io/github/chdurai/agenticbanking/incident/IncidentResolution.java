package io.github.chdurai.agenticbanking.incident;

import java.time.Instant;
import java.util.List;

public record IncidentResolution(
        String incidentId,
        Status status,
        IncidentTriage triage,
        DiagnosisReport diagnosis,
        RemediationPlan remediation,
        List<String> guardrailFlags,
        String narrative,
        List<String> executionLog,   // simulated execution trail (auto-remediation or post-approval)
        String approvalId,           // HITL queue id when a human decision is pending
        Instant handledAt
) {
    public enum Status {
        AUTO_REMEDIATED,        // all steps low-risk, executed autonomously (simulated executor)
        PENDING_HUMAN_APPROVAL, // plan ready, contains risky steps
        ESCALATED,              // SEV1/SEV2/security/low-confidence - human review queued
        BLOCKED                 // stopped by input guardrails
    }
}
