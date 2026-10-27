package io.github.chdurai.agenticbanking.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LoanDecision(
        String applicationId,
        Decision decision,
        BigDecimal approvedAmount,
        BigDecimal interestRateApr,
        List<String> conditions,
        List<String> adverseActionReasons,
        String reasoning,
        List<AgentReport> agentReports,
        int revisionCount,
        Instant decidedAt,
        String policyVersion,
        /** Full audit codes; may reveal AML/sanctions findings. Never expose to customers. */
        List<String> internalReasonCodes,
        /** Codes safe to disclose in an adverse-action notice. */
        List<String> customerReasonCodes
) {
    public enum Decision {
        APPROVED, DECLINED, MANUAL_REVIEW,
        /** Restricted compliance route (e.g. sanctions hit): no customer-facing reason is given. */
        COMPLIANCE_HOLD;

        /** Higher is stricter: APPROVED < MANUAL_REVIEW < DECLINED < COMPLIANCE_HOLD. */
        public int strictness() {
            return switch (this) {
                case APPROVED -> 0;
                case MANUAL_REVIEW -> 1;
                case DECLINED -> 2;
                case COMPLIANCE_HOLD -> 3;
            };
        }
    }

    public LoanDecision(String applicationId, Decision decision, BigDecimal approvedAmount,
                        BigDecimal interestRateApr, List<String> conditions,
                        List<String> adverseActionReasons, String reasoning,
                        List<AgentReport> agentReports, int revisionCount, Instant decidedAt) {
        this(applicationId, decision, approvedAmount, interestRateApr, conditions, adverseActionReasons,
                reasoning, agentReports, revisionCount, decidedAt, null, List.of(), List.of());
    }

    public LoanDecision withRevisionCount(int revisions) {
        return new LoanDecision(applicationId, decision, approvedAmount, interestRateApr, conditions,
                adverseActionReasons, reasoning, agentReports, revisions, decidedAt, policyVersion,
                internalReasonCodes, customerReasonCodes);
    }

    /** What a caller outside the bank may see: no internal codes; nothing but generic wording on a compliance hold. */
    public LoanDecision customerView() {
        if (decision == Decision.COMPLIANCE_HOLD) {
            return new LoanDecision(applicationId, decision, BigDecimal.ZERO, null, List.of(), List.of(),
                    "Your application cannot be processed automatically and has been referred for further review.",
                    List.of(), revisionCount, decidedAt, policyVersion, List.of(), List.of());
        }
        return new LoanDecision(applicationId, decision, approvedAmount, interestRateApr, conditions,
                adverseActionReasons, reasoning, agentReports, revisionCount, decidedAt, policyVersion,
                List.of(), customerReasonCodes);
    }
}
