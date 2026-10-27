package io.github.chdurai.agenticbanking.flow.state;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The immutable handoff unit between agents. Revisions are new findings pointing at {@code supersedes};
 * nothing is ever mutated. {@code narrative} and {@code observations} are untrusted text and are never
 * parsed by gates; {@code facts} are the machine-readable part.
 */
public record Finding(
        String findingId,
        String caseId,
        String flow,
        String agentId,
        Kind kind,
        Status status,
        Map<String, Object> facts,
        List<RiskSignal> riskSignals,
        String narrative,
        List<String> observations,
        List<Evidence> evidence,
        String confidence,
        String supersedes,
        Provenance provenance,
        boolean injectionSuspect,
        int schemaVersion,
        Instant createdAt) {

    public enum Kind { ASSESSMENT, EVIDENCE, CHECK_RESULT, NARRATIVE, DECISION_CANDIDATE, UNAVAILABLE }

    /** UNAVAILABLE is never conflated with WARN. */
    public enum Status { PASS, WARN, FAIL, UNAVAILABLE }

    public static final int SCHEMA_VERSION = 1;

    public Finding {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        riskSignals = riskSignals == null ? List.of() : List.copyOf(riskSignals);
        observations = observations == null ? List.of() : List.copyOf(observations);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
