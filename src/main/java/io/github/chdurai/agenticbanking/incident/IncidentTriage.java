package io.github.chdurai.agenticbanking.incident;

/**
 * Triage output: classification + severity + reporter sentiment (NLP layer).
 */
public record IncidentTriage(
        Category category,
        Severity severity,
        Sentiment sentiment,
        String summary,
        String rationale
) {
    public enum Category { NETWORK, DATABASE, APPLICATION, SECURITY, ACCESS, INFRASTRUCTURE, OTHER }
    public enum Severity { SEV1, SEV2, SEV3, SEV4 }
    public enum Sentiment { CALM, CONCERNED, FRUSTRATED, URGENT }
}
