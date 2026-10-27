package io.github.chdurai.agenticbanking.model;

import java.util.List;

/**
 * Structured output produced by every agent in the pipeline.
 */
public record AgentReport(
        String agentName,
        Status status,
        int riskScore,          // 0 (no risk) - 100 (severe risk)
        String summary,
        List<String> findings,
        List<String> redFlags
) {
    /** UNAVAILABLE = the check could not run (agent/model outage); never a verdict on the applicant. */
    public enum Status { PASS, WARN, FAIL, UNAVAILABLE }

    public static AgentReport unavailable(String agentName, String cause) {
        return new AgentReport(agentName, Status.UNAVAILABLE, 70,
                "Agent unavailable - could not complete this check.",
                List.of("Agent error: " + cause),
                List.of("Check not performed; manual verification required"));
    }

    public boolean failed() {
        return status == Status.FAIL;
    }
}
