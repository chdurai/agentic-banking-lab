package io.github.chdurai.agenticbanking.grievance;

import java.util.List;

/** LLM triage of a grievance: thematic classification, severity, sentiment and risk flags. */
public record GrievanceTriage(
        List<Theme> themes,
        Severity severity,
        Sentiment sentiment,
        boolean retaliationRisk,
        boolean vulnerableGroupsAffected,
        int confidencePct,
        String summary,
        String rationale
) {
    /** Illustrative environmental & social thematic taxonomy. */
    public enum Theme {
        LABOR_AND_WORKING_CONDITIONS,
        COMMUNITY_HEALTH_AND_SAFETY,
        LAND_AND_RESETTLEMENT,
        ENVIRONMENTAL_POLLUTION,
        INDIGENOUS_PEOPLES_AND_CULTURAL_HERITAGE,
        STAKEHOLDER_ENGAGEMENT,
        CORRUPTION_OR_FRAUD,
        OTHER
    }

    public enum Severity { LOW, MODERATE, HIGH, CRITICAL }

    public enum Sentiment { NEUTRAL, CONCERNED, DISTRESSED, ANGRY }
}
