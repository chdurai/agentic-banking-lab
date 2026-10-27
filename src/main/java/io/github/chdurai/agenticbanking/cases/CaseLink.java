package io.github.chdurai.agenticbanking.cases;

import java.time.Instant;

public record CaseLink(String id, String tenantId, String fromCase, String toCase, Type type,
                       String createdBy, Instant createdAt) {
    public enum Type {
        LOAN_GRIEVANCE("loan", "grievance"),
        DEPENDENCY_DEGRADED("incident", "loan"),
        DOCUMENT_EVIDENCE("document", "loan");

        private final String sourceFlow;
        private final String targetFlow;
        Type(String sourceFlow, String targetFlow) { this.sourceFlow = sourceFlow; this.targetFlow = targetFlow; }
        public String sourceFlow() { return sourceFlow; }
        public String targetFlow() { return targetFlow; }
    }

    public record Creation(CaseLink link, boolean created) {}
}
