package io.github.chdurai.agenticbanking.agents;

import io.github.chdurai.agenticbanking.capability.CapabilityGuard;
import io.github.chdurai.agenticbanking.flow.state.Provenance;
import io.github.chdurai.agenticbanking.guardrails.Spotlight;
import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.llm.LlmResult;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for all pipeline agents. Model routing, fallback, retry, breaker and
 * timeouts are owned by {@link LlmGateway}; this class only describes the call and
 * supplies a deterministic fallback when the LLM path is unavailable.
 */
public abstract class LoanAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String agentName;
    private final LlmGateway llm;
    protected final CapabilityGuard capabilities;
    private final String systemPrompt;

    protected LoanAgent(LlmGateway llm, CapabilityGuard capabilities, String agentName, String systemPrompt) {
        this.llm = llm;
        this.capabilities = capabilities;
        this.agentName = agentName;
        this.systemPrompt = systemPrompt;
    }

    public String agentName() {
        return agentName;
    }

    public AgentReport evaluate(LoanContext context) {
        return run(context).report();
    }

    /** A report plus how it was produced; the orchestrator records it as a finding with provenance. */
    public record Run(AgentReport report, Provenance provenance) {}

    public Run run(LoanContext context) {
        try (var scope = capabilities.enter(agentName, context.application().applicantId())) {
            return runScoped(context);
        }
    }

    private Run runScoped(LoanContext context) {
        long start = System.nanoTime();
        AgentReport report;
        String model = "deterministic";
        boolean fallback = false;
        try {
            LlmResult<AgentReport> result = invoke(context);
            model = result.model();
            AgentReport r = result.value();
            report = postProcess(context, new AgentReport(agentName, r.status(), r.riskScore(),
                    r.summary(), r.findings(), r.redFlags()));
        } catch (Exception e) {
            report = deterministicFallback(context, e);
            fallback = true;
        }
        log.info("[{}] model={} -> status={}, riskScore={}", agentName, model, report.status(), report.riskScore());
        return new Run(report, Provenance.of(model, fallback, (System.nanoTime() - start) / 1_000_000));
    }

    private LlmResult<AgentReport> invoke(LoanContext context) {
        String system = systemPrompt + "\n\nYou are the agent named \"" + agentName + "\". "
                + "Always set agentName to that exact value."
                + (context.v2() ? "\n\n" + Spotlight.INSTRUCTION : "");
        String user = promptFor(context);
        if (context.challenge() != null) {
            user += "\n\nA reviewer challenged your previous finding: " + context.untrusted(context.challenge())
                    + "\nRe-check it against the evidence above; change your finding only if the evidence supports it.";
        }
        return llm.call(AgentCallSpec.of(agentName, system, user, AgentReport.class, 0.1));
    }

    /** Subclasses may provide deterministic evidence-based output when the LLM is unavailable. */
    protected AgentReport deterministicFallback(LoanContext context, Exception error) {
        throw new IllegalStateException("No deterministic fallback for " + agentName, error);
    }

    /**
     * Post-processes a successful LLM report (WI-08 citation verification hook).
     * Deterministic fallbacks are already evidence-based and bypass this hook.
     */
    protected AgentReport postProcess(LoanContext context, AgentReport report) {
        return report;
    }

    /** Subclasses build the user prompt from the application + prior findings. */
    protected abstract String promptFor(LoanContext context);
}
