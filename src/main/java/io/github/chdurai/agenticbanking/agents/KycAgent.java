package io.github.chdurai.agenticbanking.agents;

import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.model.LoanDataViews;
import io.github.chdurai.agenticbanking.tools.PepScreeningTools;
import io.github.chdurai.agenticbanking.tools.SanctionsScreeningTools;
import org.springframework.stereotype.Component;

/**
 * Agent 2: KYC/AML screening. Uses sanctions and PEP tools - a sanctions hit is
 * an automatic FAIL (regulatory hard stop).
 */
@Component
public class KycAgent extends LoanAgent {

    private static final String SYSTEM = """
            You are a KYC/AML analyst at a bank. The deterministic sanctions screening and
            PEP check results are provided in the prompt as evidence. Treat them as the system
            of record and never guess. Status rules: FAIL on any confirmed sanctions match
            (this is a regulatory hard stop); WARN on PEP status or incomplete identity data;
            PASS otherwise.
            """;

    private final SanctionsScreeningTools sanctionsTools;
    private final PepScreeningTools pepTools;

    public KycAgent(LlmGateway llm, io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities,
                    SanctionsScreeningTools sanctionsTools, PepScreeningTools pepTools) {
        super(llm, capabilities, "kyc", SYSTEM);
        this.sanctionsTools = sanctionsTools;
        this.pepTools = pepTools;
    }

    @Override
    protected AgentReport deterministicFallback(LoanContext context, Exception error) {
        LoanDataViews.KycView v = LoanDataViews.kyc(context.application());
        boolean sanctionsHit = sanctionsTools.screenSanctions(v.fullName()).sanctionsMatch();
        boolean pep = pepTools.checkPepStatus(v.fullName()).isPep();
        AgentReport.Status status = sanctionsHit ? AgentReport.Status.FAIL
                : pep ? AgentReport.Status.WARN : AgentReport.Status.PASS;
        return new AgentReport("kyc", status, sanctionsHit ? 95 : pep ? 55 : 10,
                "Deterministic KYC screening completed; model reasoning unavailable: "
                        + error.getClass().getSimpleName(),
                java.util.List.of("Sanctions match: " + sanctionsHit, "PEP status: " + pep),
                sanctionsHit ? java.util.List.of("Confirmed sanctions watchlist match") : java.util.List.of());
    }

    @Override
    protected String promptFor(LoanContext context) {
        capabilities.checkView("kyc-case");
        LoanDataViews.KycView v = LoanDataViews.kyc(context.application());
        var sanctions = sanctionsTools.screenSanctions(v.fullName());
        var pep = pepTools.checkPepStatus(v.fullName());
        return """
                Perform KYC/AML screening for applicant:
                - applicantId: %s
                - fullName: %s
                - ssnLast4: %s

                Deterministic screening evidence (execute no further tools; reason over this evidence):
                - Sanctions screening result: %s
                - PEP check result: %s

                Prior agent findings:
                %s
                """.formatted(v.applicantId(), context.untrusted(v.fullName()), v.ssnLast4(), sanctions, pep,
                context.priorFindings(agentName()));
    }

    @Override
    protected AgentReport postProcess(LoanContext context, AgentReport report) {
        String ssn = LoanDataViews.kyc(context.application()).ssnLast4();
        String summary = redact(report.summary(), ssn);
        java.util.List<String> findings = report.findings() == null ? java.util.List.of()
                : report.findings().stream().map(f -> redact(f, ssn)).toList();
        return new AgentReport(report.agentName(), report.status(), report.riskScore(),
                summary, findings, report.redFlags());
    }

    private static String redact(String text, String token) {
        if (text == null || token == null || token.isBlank()) {
            return text;
        }
        return text.replace(token, "[REDACTED-SSN4]");
    }
}
