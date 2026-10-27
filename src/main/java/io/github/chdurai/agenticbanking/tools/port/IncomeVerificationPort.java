package io.github.chdurai.agenticbanking.tools.port;

public interface IncomeVerificationPort extends ToolPort {

    record IncomeVerificationRequest(String applicantId, String employer, double statedAnnualIncome) {
    }

    record IncomeVerificationResult(
            String applicantId,
            String employer,
            boolean verified,
            String reason,
            long verifiedAnnualIncome,
            boolean tenureConfirmed) {
    }

    IncomeVerificationResult verify(IncomeVerificationRequest request);

    @Override
    default String id() {
        return "income.verify-employment";
    }

    @Override
    default SideEffect sideEffect() {
        return SideEffect.NONE;
    }
}
