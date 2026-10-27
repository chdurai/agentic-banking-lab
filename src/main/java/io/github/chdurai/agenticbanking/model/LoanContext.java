package io.github.chdurai.agenticbanking.model;

import io.github.chdurai.agenticbanking.flow.FlowKit;
import io.github.chdurai.agenticbanking.flow.state.CaseState;
import io.github.chdurai.agenticbanking.flow.state.InMemoryCaseState;
import io.github.chdurai.agenticbanking.flow.state.Provenance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutable context threaded through the agent pipeline.
 * Each agent reads the application plus prior reports and appends its own report.
 * Thread-safe: agents in the same stage may run concurrently.
 */
public class LoanContext {

    private final LoanApplication application;
    private final FlowKit kit;
    private final CaseState state;
    private final List<AgentReport> reports = new CopyOnWriteArrayList<>();
    private boolean terminated;
    private String terminationReason;
    private String challenge;

    public LoanContext(LoanApplication application) {
        this(application, FlowKit.legacy());
    }

    public LoanContext(LoanApplication application, FlowKit kit) {
        this.application = application;
        this.kit = kit;
        this.state = new InMemoryCaseState(application.applicantId(), "loan", kit.activity());
    }

    private LoanContext(LoanContext source) {
        this.application = source.application;
        this.kit = source.kit;
        this.state = source.state.copy();
        this.reports.addAll(source.reports);
        this.terminated = source.terminated;
        this.terminationReason = source.terminationReason;
        this.challenge = source.challenge;
    }

    /** Append-only typed mirror of the reports (the source of truth when platform.flow.state=v2). */
    public CaseState state() {
        return state;
    }

    public boolean v2() {
        return kit.v2();
    }

    /** Fences applicant-supplied text for prompts when typed-state mode is on. */
    public String untrusted(String text) {
        return kit.v2() ? kit.prompts().untrusted(text) : text;
    }

    public LoanApplication application() {
        return application;
    }

    public List<AgentReport> reports() {
        return List.copyOf(reports);
    }

    public void addReport(AgentReport report) {
        addReport(report, Provenance.of("unknown", false, 0));
    }

    public void addReport(AgentReport report, Provenance provenance) {
        reports.add(report);
        state.append(kit.mapper().toFinding(application.applicantId(), "loan", report, provenance));
    }

    /**
     * Replaces an agent's current report after a challenge recheck. The typed state stays append-only: the
     * revision is a new finding that supersedes the old one.
     */
    public void revise(AgentReport report, Provenance provenance) {
        reports.removeIf(r -> r.agentName().equals(report.agentName()));
        reports.add(report);
        state.append(kit.mapper().toFinding(application.applicantId(), "loan", report, provenance));
    }

    /** A reviewer's claim the agent must re-check against its evidence (WI-36); null on a normal run. */
    public String challenge() {
        return challenge;
    }

    public LoanContext withChallenge(String claim) {
        LoanContext copy = new LoanContext(this);
        copy.challenge = claim;
        return copy;
    }

    /** Immutable-in-practice view for one stage: parallel agents all read the same findings. */
    public LoanContext snapshot() {
        return new LoanContext(this);
    }

    public boolean isTerminated() {
        return terminated;
    }

    public String terminationReason() {
        return terminationReason;
    }

    public void terminate(String reason) {
        this.terminated = true;
        this.terminationReason = reason;
    }

    /** Findings an agent is declared to read: typed and Spotlighted in v2, the legacy transcript otherwise. */
    public String priorFindings(String forAgent) {
        return kit.v2() ? kit.prompts().render(state,
                kit.specs().find(forAgent).map(io.github.chdurai.agenticbanking.flow.spec.AgentSpec::reads).orElse(null)) : priorFindings();
    }

    /** Compact transcript of prior agent findings for downstream prompts (legacy mode). */
    public String priorFindings() {
        if (reports.isEmpty()) {
            return "No prior agent findings.";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentReport r : reports) {
            sb.append("- ").append(r.agentName())
              .append(" [").append(r.status()).append(", riskScore=").append(r.riskScore()).append("]: ")
              .append(r.summary());
            if (!r.redFlags().isEmpty()) {
                sb.append(" Red flags: ").append(String.join("; ", r.redFlags()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
