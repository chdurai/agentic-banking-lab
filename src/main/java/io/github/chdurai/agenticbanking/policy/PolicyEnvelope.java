package io.github.chdurai.agenticbanking.policy;

import io.github.chdurai.agenticbanking.model.LoanDecision.Decision;

import java.math.BigDecimal;
import java.util.List;

/**
 * Deterministic outcome of the policy engine for one case: the weakest decision the bank will
 * accept (floor), the pricing and amount the policy dictates, and the audit codes behind them.
 */
public record PolicyEnvelope(
        Decision floor,
        BigDecimal maxAmount,
        BigDecimal apr,
        List<String> internalReasonCodes,
        List<String> customerReasonCodes,
        String policyVersion) {

    public boolean complianceHold() {
        return floor == Decision.COMPLIANCE_HOLD;
    }
}
