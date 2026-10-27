package io.github.chdurai.agenticbanking.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * WI-18 typed minimization views: each agent sees only the fields it needs.
 */
public final class LoanDataViews {

    private LoanDataViews() {
    }

    public record IntakeView(
            String applicantId,
            String fullName,
            BigDecimal annualIncome,
            int employmentYears,
            BigDecimal requestedAmount,
            String loanPurpose,
            int termMonths,
            BigDecimal existingMonthlyDebt,
            String employer,
            List<String> documents,
            double debtToIncomeRatio,
            double loanToAnnualIncome) {
    }

    public record KycView(String applicantId, String fullName, String ssnLast4) {
    }

    /**
     * No name/SSN in risk scoring view.
     */
    public record RiskView(
            String applicantId,
            String employer,
            BigDecimal annualIncome,
            int employmentYears,
            BigDecimal requestedAmount,
            String loanPurpose,
            int termMonths,
            BigDecimal existingMonthlyDebt) {
    }

    public static IntakeView intake(LoanApplication a) {
        double loanToIncome = a.annualIncome().signum() == 0
                ? 0.0
                : a.requestedAmount().divide(a.annualIncome(), 4, RoundingMode.HALF_UP).doubleValue();
        return new IntakeView(
                a.applicantId(),
                a.fullName(),
                a.annualIncome(),
                a.employmentYears(),
                a.requestedAmount(),
                a.loanPurpose(),
                a.termMonths(),
                a.existingMonthlyDebt(),
                a.employer(),
                a.documents() == null ? List.of() : List.copyOf(a.documents()),
                a.debtToIncomeRatio().doubleValue(),
                loanToIncome);
    }

    public static KycView kyc(LoanApplication a) {
        return new KycView(a.applicantId(), a.fullName(), a.ssnLast4());
    }

    public static RiskView risk(LoanApplication a) {
        return new RiskView(a.applicantId(), a.employer(), a.annualIncome(), a.employmentYears(),
                a.requestedAmount(), a.loanPurpose(), a.termMonths(), a.existingMonthlyDebt());
    }
}
