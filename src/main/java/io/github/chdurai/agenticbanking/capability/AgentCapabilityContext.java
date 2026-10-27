package io.github.chdurai.agenticbanking.capability;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-local "who is running" scope. Opened by agents around their entry method;
 * {@link CapabilityGuard} reads it to attribute tool/retrieval/view access to an agent.
 * Nested scopes restore the parent on close, and absence of a scope means the caller is
 * platform code (controller, MCP tool, test) rather than an agent — profiles constrain
 * agents only.
 */
public final class AgentCapabilityContext implements AutoCloseable {

    private static final ThreadLocal<AgentCapabilityContext> CURRENT = new ThreadLocal<>();

    private final String agentId;
    private final String caseId;
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AgentCapabilityContext parent;

    private AgentCapabilityContext(String agentId, String caseId) {
        this.agentId = agentId;
        this.caseId = caseId;
        this.parent = CURRENT.get();
        CURRENT.set(this);
    }

    static AgentCapabilityContext open(String agentId) {
        return open(agentId, null);
    }

    static AgentCapabilityContext open(String agentId, String caseId) {
        return new AgentCapabilityContext(agentId, caseId);
    }

    public static Optional<AgentCapabilityContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public String agentId() {
        return agentId;
    }

    public String caseId() {
        return caseId;
    }

    /** Counts one tool invocation and returns the running total for this agent scope. */
    int countToolCall() {
        return toolCalls.incrementAndGet();
    }

    @Override
    public void close() {
        CURRENT.set(parent);
    }
}
