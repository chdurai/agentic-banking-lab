package io.github.chdurai.agenticbanking.grievance;

import java.util.List;

/**
 * Deterministic routing and SLA policy. The LLM classifies; policy decides.
 * SLA values are illustrative defaults, to be replaced by the programme's GRM procedures.
 */
final class GrievanceRouting {

    record Decision(GrievanceResolution.Route route, int slaHours, boolean humanReviewRequired, String reason) {}

    /** Below this triage confidence a person must review. */
    static final int MIN_CONFIDENCE = 60;

    private GrievanceRouting() {}

    static Decision decide(GrievanceTriage t) {
        boolean corruption = t.themes() != null && t.themes().contains(GrievanceTriage.Theme.CORRUPTION_OR_FRAUD);
        boolean lowConfidence = t.confidencePct() < MIN_CONFIDENCE;
        GrievanceTriage.Severity severity = t.severity() == null ? GrievanceTriage.Severity.HIGH : t.severity();

        if (corruption) {
            return new Decision(GrievanceResolution.Route.CONFIDENTIAL_CASE_TEAM, 48, true,
                    "Alleged corruption or fraud requires restricted handling");
        }
        if (severity == GrievanceTriage.Severity.CRITICAL || t.retaliationRisk()) {
            return new Decision(GrievanceResolution.Route.SAFEGUARDS_SPECIALIST, 24, true,
                    t.retaliationRisk() ? "Retaliation risk" : "Critical severity");
        }
        if (severity == GrievanceTriage.Severity.HIGH || t.vulnerableGroupsAffected() || lowConfidence) {
            String reason = lowConfidence ? "Low triage confidence"
                    : t.vulnerableGroupsAffected() ? "Vulnerable groups affected" : "High severity";
            return new Decision(GrievanceResolution.Route.SAFEGUARDS_SPECIALIST, 72, true, reason);
        }
        if (severity == GrievanceTriage.Severity.MODERATE) {
            return new Decision(GrievanceResolution.Route.PROJECT_GRM_OFFICER, 168, false, "Moderate severity");
        }
        return new Decision(GrievanceResolution.Route.PROJECT_GRM_OFFICER, 360, false, "Low severity");
    }
}
