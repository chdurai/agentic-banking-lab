package io.github.chdurai.agenticbanking.tools.port;

public interface CreditBureauPort extends ToolPort {

    record CreditReport(
            String applicantId,
            int ficoScore,
            int openAccounts,
            int delinquencies,
            double utilizationPct,
            int hardInquiriesLast12Months,
            String oldestCreditLineYears) {
    }

    record PublicRecords(
            String applicantId,
            int bankruptcies,
            int foreclosures,
            int chargeOffs,
            String chargeOffDetail) {
    }

    CreditReport creditReport(String applicantId);

    PublicRecords publicRecords(String applicantId);

    @Override
    default String id() {
        return "bureau.credit-report";
    }

    @Override
    default SideEffect sideEffect() {
        return SideEffect.NONE;
    }
}
