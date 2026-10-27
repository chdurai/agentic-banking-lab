package io.github.chdurai.agenticbanking.approval;

import java.util.List;

/**
 * Reviewer packet for human decisions: compact case facts, agent findings, policy/invariant context
 * and approve/reject side-effect preview.
 */
public record ApprovalEvidencePacket(
        int schemaVersion,
        String flow,
        String subjectId,
        List<String> minimizedCaseFacts,
        List<AgentFinding> agentFindings,
        List<String> policyEnvelope,
        List<String> invariantResults,
        String criticResult,
        List<String> artifactSet,
        SideEffectPreview sideEffects,
        List<String> uncertaintySignals,
        String aiRecommendation,
        int maxRiskScore,
        boolean nonTrivialRisk
) {
    public ApprovalEvidencePacket {
        minimizedCaseFacts = minimizedCaseFacts == null ? List.of() : List.copyOf(minimizedCaseFacts);
        agentFindings = agentFindings == null ? List.of() : List.copyOf(agentFindings);
        policyEnvelope = policyEnvelope == null ? List.of() : List.copyOf(policyEnvelope);
        invariantResults = invariantResults == null ? List.of() : List.copyOf(invariantResults);
        artifactSet = artifactSet == null ? List.of() : List.copyOf(artifactSet);
        uncertaintySignals = uncertaintySignals == null ? List.of() : List.copyOf(uncertaintySignals);
        if (sideEffects == null) {
            sideEffects = new SideEffectPreview(List.of(), List.of());
        }
    }

    public static ApprovalEvidencePacket legacy(String flow, String subjectId, String title, List<String> details) {
        return new ApprovalEvidencePacket(1, flow, subjectId,
                List.of("Title: " + (title == null ? "" : title)),
                List.of(),
                List.of(),
                List.of(),
                "not available",
                List.of(),
                new SideEffectPreview(List.of("Execute approved action if configured."), List.of("No action executed.")),
                List.of(),
                "See reviewer details.",
                0,
                false);
    }

    public ApprovalEvidencePacket redactedForBlindFirst() {
        return new ApprovalEvidencePacket(schemaVersion, flow, subjectId,
                List.of("BLIND_FIRST_REQUIRED: submit an independent assessment before viewing AI evidence."),
                List.of(), List.of(), List.of(), "hidden until blind-first is submitted",
                List.of(), new SideEffectPreview(List.of(), List.of()), List.of(),
                "hidden", maxRiskScore, nonTrivialRisk);
    }

    public record AgentFinding(
            String agentId,
            String status,
            int riskScore,
            String summary,
            List<String> citations
    ) {
        public AgentFinding {
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    public record SideEffectPreview(
            List<String> onApprove,
            List<String> onReject
    ) {
        public SideEffectPreview {
            onApprove = onApprove == null ? List.of() : List.copyOf(onApprove);
            onReject = onReject == null ? List.of() : List.copyOf(onReject);
        }
    }
}
