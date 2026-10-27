package io.github.chdurai.agenticbanking.flow.protocol;

/**
 * One typed, rule-id'd objection to the current decision candidate or to an agent's finding (v3.1 §3.7).
 *
 * @param targetAgent "decision" when the candidate itself is challenged, else the owning agent's id
 */
public record ChallengeIssue(String ruleId, Severity severity, String targetAgent, String claim, Source source) {

    /** BLOCKING cannot be closed by a rebuttal or by silence; ADVISORY only informs the reviewer. */
    public enum Severity { BLOCKING, ADVISORY }

    public enum Source { DETECTOR, INVARIANT, CRITIC }

    public static final String DECISION = "decision";

    public boolean blocking() {
        return severity == Severity.BLOCKING;
    }

    public boolean aimedAtDecision() {
        return DECISION.equals(targetAgent);
    }
}
