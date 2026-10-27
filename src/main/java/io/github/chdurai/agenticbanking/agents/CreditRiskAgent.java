package io.github.chdurai.agenticbanking.agents;

import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.model.LoanDataViews;
import io.github.chdurai.agenticbanking.tools.CreditBureauTools;
import io.github.chdurai.agenticbanking.tools.IncomeVerificationTools;
import org.springframework.stereotype.Component;

/**
 * Agent 3: credit risk assessment. Pulls bureau data and verifies income via tools,
 * then scores default risk.
 */
@Component
public class CreditRiskAgent extends LoanAgent {

    private static final String SYSTEM = """
            You are a senior credit risk officer at a bank. The deterministic credit bureau,
            public-record and income verification results are provided in the prompt as evidence.
            Treat them as the system of record and never guess. Assess: FICO band, DTI after
            the new loan (assume new monthly payment = requestedAmount / termMonths),
            utilization, delinquencies, charge-offs, income verification result, and
            loan-to-income ratio.
            Status rules: FAIL if FICO < 580, or verified income materially contradicts stated
            income, or DTI after new loan > 50%; WARN for FICO 580-669, DTI 36-50%, or any
            charge-off; PASS otherwise. riskScore = probability-of-default style 0-100.
            """;
    private final CreditBureauTools bureauTools;
    private final IncomeVerificationTools incomeTools;

    public CreditRiskAgent(LlmGateway llm, io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities,
                           CreditBureauTools bureauTools, IncomeVerificationTools incomeTools) {
        super(llm, capabilities, "credit-risk", SYSTEM);
        this.bureauTools = bureauTools;
        this.incomeTools = incomeTools;
    }

    @Override
    protected AgentReport deterministicFallback(LoanContext context, Exception error) {
        AgentReport report = deterministicAssessment(context);
        return new AgentReport(report.agentName(), report.status(), report.riskScore(),
                report.summary() + "; model reasoning unavailable: " + error.getClass().getSimpleName(),
                report.findings(), report.redFlags());
    }

    protected AgentReport deterministicAssessment(LoanContext context) {
        LoanDataViews.RiskView v = LoanDataViews.risk(context.application());
        var report = bureauTools.getCreditReport(v.applicantId());
        var records = bureauTools.getPublicRecords(v.applicantId());
        var income = incomeTools.verifyEmployment(v.applicantId(), v.employer(), v.annualIncome().doubleValue());
        int fico = report.ficoScore();
        boolean chargeOff = records.chargeOffs() > 0;
        boolean incomeVerified = income.verified();
        double newPayment = v.requestedAmount().doubleValue() / Math.max(1, v.termMonths());
        double dtiAfterLoan = ((v.existingMonthlyDebt().doubleValue() + newPayment)
                / (v.annualIncome().doubleValue() / 12.0)) * 100.0;
        AgentReport.Status status = fico < 580 || !incomeVerified || dtiAfterLoan > 50
                ? AgentReport.Status.FAIL
                : fico < 670 || chargeOff || dtiAfterLoan > 36 ? AgentReport.Status.WARN : AgentReport.Status.PASS;
        int risk = Math.min(95, Math.max(5, (int) Math.round((750 - fico) / 2.0 + dtiAfterLoan / 2.0 + (chargeOff ? 20 : 0))));
        return new AgentReport("credit-risk", status, risk,
                "Deterministic credit risk assessment completed",
                java.util.List.of("FICO: " + fico, "DTI after new loan: %.1f%%".formatted(dtiAfterLoan),
                        "Income verified: " + incomeVerified, "Charge-off present: " + chargeOff),
                chargeOff ? java.util.List.of("Public records include a charge-off") : java.util.List.of());
    }

    @Override
    protected String promptFor(LoanContext context) {
        capabilities.checkView("loan-application");
        LoanDataViews.RiskView v = LoanDataViews.risk(context.application());
        var bureauReport = bureauTools.getCreditReport(v.applicantId());
        var publicRecords = bureauTools.getPublicRecords(v.applicantId());
        var incomeVerification = incomeTools.verifyEmployment(v.applicantId(),
                v.employer(), v.annualIncome().doubleValue());
        return """
                Assess credit risk for:
                - applicantId: %s
                - employer: %s
                - statedAnnualIncome: %s
                - employmentYears: %d
                - requestedAmount: %s over %d months
                - existingMonthlyDebt: %s
                - loanPurpose: %s

                Deterministic evidence (execute no further tools; reason over this evidence):
                - Credit bureau report: %s
                - Public records: %s
                - Income verification: %s
                """.formatted(v.applicantId(),
                v.employer() == null ? "not provided" : context.untrusted(v.employer()),
                v.annualIncome(), v.employmentYears(), v.requestedAmount(), v.termMonths(),
                v.existingMonthlyDebt(), context.untrusted(v.loanPurpose()), bureauReport, publicRecords,
                incomeVerification);
    }

}
