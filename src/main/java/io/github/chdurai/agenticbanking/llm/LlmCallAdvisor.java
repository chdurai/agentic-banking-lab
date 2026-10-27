package io.github.chdurai.agenticbanking.llm;

/**
 * Extension point for cross-cutting policy that must see every model call (input/output
 * guardrails, canary-token leak checks, PII redaction). Advisors run in bean order; throwing
 * {@link LlmException} aborts the call. Intentionally small so WI-09 can plug in without
 * touching the gateway.
 */
public interface LlmCallAdvisor {

    /** May return a transformed user prompt; the default passes it through. */
    default String beforeCall(String agentId, String system, String user) {
        return user;
    }

    /** Inspect the raw model text before it is parsed; throw to reject it. */
    default void afterCall(String agentId, String modelText) {
    }
}
