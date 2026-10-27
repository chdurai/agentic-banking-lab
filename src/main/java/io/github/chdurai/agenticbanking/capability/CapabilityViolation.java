package io.github.chdurai.agenticbanking.capability;

/**
 * Raised when an agent attempts something outside its capability profile
 * (tool, data view or corpus not allow-listed, or a budget exceeded). Fail-safe:
 * the agent's action is denied and the violation is audited.
 */
public class CapabilityViolation extends RuntimeException {

    public CapabilityViolation(String agentId, String detail) {
        super("Agent '" + agentId + "' violated its capability profile: " + detail);
    }
}
