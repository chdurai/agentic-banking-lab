package io.github.chdurai.agenticbanking.capability;

import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Runtime enforcement of capability profiles (WI-20). Agents open a scope via
 * {@link #enter(String)} around their entry method; tool classes and retrievers call
 * {@code check*} which attributes the access to the scoped agent. A violation is
 * audited as a {@code capability.violation} platform event and fails the agent safely
 * by throwing {@link CapabilityViolation}. Deny-by-default: an agent with no profile
 * (or no scope at all when platform code calls directly is a non-agent caller and is
 * allowed — profiles constrain agents only).
 */
@Component
public class CapabilityGuard {

    private final CapabilityRegistry registry;
    private final PlatformEventPublisher events;

    public CapabilityGuard(CapabilityRegistry registry, PlatformEventPublisher events) {
        this.registry = registry;
        this.events = events;
    }

    /**
     * Guard with an empty registry and a discarded audit stream. Used by no-arg tool
     * constructors (tests); checks only apply when an agent scope is open, which direct
     * tool tests never do.
     */
    public static CapabilityGuard permissive() {
        return new CapabilityGuard(new CapabilityRegistry(new CapabilityProperties()),
                new PlatformEventPublisher(event -> { }));
    }

    /** Opens the capability scope for an agent's entry method (try-with-resources). */
    public AgentCapabilityContext enter(String agentId) {
        return enter(agentId, null);
    }

    /** Opens the capability scope with a case/subject id for tool-audit correlation. */
    public AgentCapabilityContext enter(String agentId, String caseId) {
        return AgentCapabilityContext.open(agentId, caseId);
    }

    public void checkTool(String toolId) {
        AgentCapabilityContext ctx = AgentCapabilityContext.current().orElse(null);
        if (ctx == null) {
            return;
        }
        CapabilityProfile profile = registry.profile(ctx.agentId()).orElse(null);
        if (profile == null) {
            deny(ctx.agentId(), "no capability profile declared; tool '" + toolId + "' denied");
            return;
        }
        int call = ctx.countToolCall();
        if (!profile.tools().contains(toolId)) {
            deny(ctx.agentId(), "tool '" + toolId + "' not in allow-list " + profile.tools());
        } else if (call > profile.maxToolCalls()) {
            deny(ctx.agentId(), "tool call budget exceeded (" + call + " > "
                    + profile.maxToolCalls() + ") on '" + toolId + "'");
        }
    }

    public void checkRetrieval(String corpus) {
        AgentCapabilityContext ctx = AgentCapabilityContext.current().orElse(null);
        if (ctx == null) {
            return;
        }
        CapabilityProfile profile = registry.profile(ctx.agentId()).orElse(null);
        if (profile == null || !profile.retrieval().contains(corpus)) {
            deny(ctx.agentId(), "corpus '" + corpus + "' not allow-listed"
                    + (profile == null ? " (no profile declared)" : " in " + profile.retrieval()));
        }
    }

    public void checkView(String view) {
        AgentCapabilityContext ctx = AgentCapabilityContext.current().orElse(null);
        if (ctx == null) {
            return;
        }
        CapabilityProfile profile = registry.profile(ctx.agentId()).orElse(null);
        if (profile == null || !profile.dataViews().contains(view)) {
            deny(ctx.agentId(), "data view '" + view + "' not allow-listed"
                    + (profile == null ? " (no profile declared)" : " in " + profile.dataViews()));
        }
    }

    /** Audits the violation, then fails the agent safely. */
    void deny(String agentId, String detail) {
        events.publish(PlatformEvent.of("capability.violation", "security", agentId,
                "profiles=" + registry.version() + " " + detail));
        throw new CapabilityViolation(agentId, detail);
    }
}
