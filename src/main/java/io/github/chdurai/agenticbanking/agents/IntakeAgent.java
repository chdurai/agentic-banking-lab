package io.github.chdurai.agenticbanking.agents;

import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.model.LoanDataViews;
import org.springframework.stereotype.Component;

/**
 * Agent 1: validates completeness/consistency of the application and enriches it
 * with derived metrics (DTI, loan-to-income).
 */
@Component
public class IntakeAgent extends LoanAgent {

    private static final String SYSTEM = """
            You are a loan intake officer at a bank. Assess the application for completeness,
            internal consistency, and obvious eligibility issues (age of credit, loan purpose
            legitimacy, DTI sanity). Compute nothing you cannot derive from the input.
            Status rules: FAIL if materially incomplete or inconsistent; WARN if missing
            nice-to-have data; PASS otherwise. riskScore reflects data-quality risk only.
            """;

    public IntakeAgent(LlmGateway llm, io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities) {
        super(llm, capabilities, "intake", SYSTEM);
    }

    @Override
    protected String promptFor(LoanContext context) {
        capabilities.checkView("loan-application");
        LoanDataViews.IntakeView v = LoanDataViews.intake(context.application());
        return """
                Evaluate this loan application intake:
                - applicantId: %s
                - fullName: %s
                - annualIncome: %s
                - employmentYears: %d
                - requestedAmount: %s
                - loanPurpose: %s
                - termMonths: %d
                - existingMonthlyDebt: %s
                - employer: %s
                - documentsProvided: %s
                - derived debtToIncomeRatio: %.2f
                - derived loanToAnnualIncome: %.2f
                """.formatted(v.applicantId(), context.untrusted(v.fullName()), v.annualIncome(), v.employmentYears(),
                v.requestedAmount(), context.untrusted(v.loanPurpose()), v.termMonths(), v.existingMonthlyDebt(),
                v.employer() == null ? "not provided" : context.untrusted(v.employer()),
                v.documents().isEmpty() ? "none" : context.untrusted(String.join(", ", v.documents())),
                v.debtToIncomeRatio(),
                v.loanToAnnualIncome());
    }
}
