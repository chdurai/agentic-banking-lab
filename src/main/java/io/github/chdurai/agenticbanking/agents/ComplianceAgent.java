package io.github.chdurai.agenticbanking.agents;

import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.policy.LoanPolicyProperties;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.rag.PolicyRetriever;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 4: regulatory compliance review grounded in retrieved policy clauses (RAG).
 * Must cite policy sections in findings.
 */
@Component
public class ComplianceAgent extends LoanAgent {

    private final PolicyRetriever policyRetriever;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.chdurai.agenticbanking.investigation.InvestigationStore lineage;

    private static final String SYSTEM = """
            You are a banking compliance officer reviewing a loan file for regulatory issues:
            ECOA/Reg B (prohibited-basis discrimination - never use race, religion, sex,
            marital status, or national origin in reasoning), TILA/Reg Z disclosure readiness,
            fair-lending consistency, BSA/AML red flags (structuring, unusual purpose),
            and policy limits (unsecured personal loans capped at $100,000, term <= 84 months).
            Review prior agent findings for consistency. Base every conclusion on the
            retrieved policy clauses provided and cite section IDs (e.g. POL-CRD-001 §3)
            in your findings. Status rules: FAIL on fair-lending violations, AML red flags,
            or policy-limit breaches; WARN on documentation gaps; PASS otherwise.
            """;

    private final LoanPolicyProperties policy;
    private final io.github.chdurai.agenticbanking.config.AgentProperties agentProperties;

    public ComplianceAgent(LlmGateway llm, io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities,
                           PolicyRetriever policyRetriever, LoanPolicyProperties policy,
                           io.github.chdurai.agenticbanking.config.AgentProperties agentProperties) {
        super(llm, capabilities, "compliance", SYSTEM);
        this.policyRetriever = policyRetriever;
        this.policy = policy;
        this.agentProperties = agentProperties;
    }

    @Override
    protected AgentReport deterministicFallback(LoanContext context, Exception error) {
        LoanApplication a = context.application();
        boolean amountBreach = a.requestedAmount().compareTo(policy.maxAmount()) > 0;
        boolean termBreach = a.termMonths() > policy.maxTermMonths();
        AgentReport.Status status = amountBreach || termBreach ? AgentReport.Status.FAIL : AgentReport.Status.PASS;
        return new AgentReport("compliance", status, amountBreach || termBreach ? 90 : 15,
                "Deterministic policy-limit review completed; model reasoning unavailable: "
                        + error.getClass().getSimpleName(),
                java.util.List.of("Unsecured amount cap $" + policy.maxAmount().toPlainString() + " breach: " + amountBreach,
                        "Term cap " + policy.maxTermMonths() + " months breach: " + termBreach,
                        "Policy basis: POL-CRD-001 §3"),
                amountBreach || termBreach ? java.util.List.of("Policy limit breach") : java.util.List.of());
    }

    @Override
    protected AgentReport postProcess(LoanContext context, AgentReport report) {
        return verifyCitations(report, retrievedClauses(context), agentName());
    }

    private List<String> retrievedClauses(LoanContext context) {
        LoanApplication a = context.application();
        String query = "loan amount " + a.requestedAmount() + " term " + a.termMonths()
                + " months, purpose " + a.loanPurpose() + ", DTI, pricing, adverse action, "
                + "sanctions PEP identity, fair lending prohibited basis";
        String observation = lineage == null ? null : lineage.sourceIntent("RETRIEVAL", agentName(),
                "policies", "versioned-corpus-snapshot", java.util.Map.of("query", query, "topK", 4));
        List<String> clauses = policyRetriever.retrieve(query, 4, agentProperties.getRagMinScore());
        if (lineage != null) lineage.sourceResult(observation, clauses, "SUCCEEDED");
        return clauses;
    }

    /**
     * WI-08 citation verification: citations not present in the retrieved set are scrubbed
     * from summary/findings and the report is flagged; if nothing was retrieved at all the
     * case routes to review instead of letting the model invent policy.
     */
    static AgentReport verifyCitations(AgentReport report, List<String> retrieved, String agentName) {
        if (retrieved.isEmpty()) {
            List<String> flags = new java.util.ArrayList<>(
                    report.redFlags() == null ? List.of() : report.redFlags());
            flags.add("POLICY_RETRIEVAL_EMPTY");
            return new AgentReport(agentName, AgentReport.Status.WARN, Math.max(report.riskScore(), 50),
                    report.summary() + " [no policy clauses retrieved - routed to human review]",
                    report.findings(), List.copyOf(flags));
        }
        String summary = io.github.chdurai.agenticbanking.rag.CitationVerifier.scrub(report.summary(), retrieved);
        List<String> findings = new java.util.ArrayList<>();
        int stripped = 0;
        for (String f : report.findings() == null ? List.<String>of() : report.findings()) {
            String scrubbed = io.github.chdurai.agenticbanking.rag.CitationVerifier.scrub(f, retrieved);
            if (!scrubbed.equals(f)) {
                stripped++;
            }
            findings.add(scrubbed);
        }
        if (stripped == 0 && summary.equals(report.summary())) {
            return report;
        }
        List<String> flags = new java.util.ArrayList<>(
                report.redFlags() == null ? List.of() : report.redFlags());
        flags.add("UNVERIFIED_CITATIONS_STRIPPED");
        return new AgentReport(agentName, report.status(), report.riskScore(), summary,
                List.copyOf(findings), List.copyOf(flags));
    }

    @Override
    protected String promptFor(LoanContext context) {
        capabilities.checkView("loan-application");
        LoanApplication a = context.application();
        capabilities.checkRetrieval("policies");
        List<String> clauses = retrievedClauses(context);

        return """
                Review this loan file for compliance:
                - requestedAmount: %s
                - termMonths: %d
                - loanPurpose: %s
                - annualIncome: %s

                Retrieved policy clauses (cite these):
                %s

                Full prior agent findings:
                %s
                """.formatted(a.requestedAmount(), a.termMonths(), context.untrusted(a.loanPurpose()),
                a.annualIncome(),
                clauses.isEmpty()
                        ? "No clauses retrieved. State that explicitly and do not cite or invent policy."
                        : String.join("\n\n", clauses),
                context.priorFindings(agentName()));
    }
}
