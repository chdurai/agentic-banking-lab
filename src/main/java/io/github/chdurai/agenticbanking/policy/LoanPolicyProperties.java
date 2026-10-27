package io.github.chdurai.agenticbanking.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.util.List;

/**
 * Single source of truth for loan decision thresholds ({@code platform.policy.loan.*}).
 * The PolicyEngine enforces them and prompts/fallbacks render them, so a rule change is made
 * once, versioned in {@code version}, and recorded on every decision.
 */
@ConfigurationProperties("platform.policy.loan")
public record LoanPolicyProperties(
        @DefaultValue("loan-policy-2026.1") String version,
        @DefaultValue("6.5") BigDecimal aprBase,
        @DefaultValue("10") BigDecimal aprRiskDivisor,
        @DefaultValue("36.0") BigDecimal aprCap,
        @DefaultValue("0.05") BigDecimal aprTolerance,
        @DefaultValue("2") int warnReviewCount,
        @DefaultValue("60") int riskReviewThreshold,
        /** max = any single agent score triggers review (current prompt behaviour); avg = mean score. */
        @DefaultValue("max") String riskReviewMetric,
        @DefaultValue("100000") BigDecimal maxAmount,
        @DefaultValue("84") int maxTermMonths,
        /** A FAIL from this agent means a sanctions match: restricted compliance route, never a customer decline. */
        @DefaultValue("kyc") String sanctionsAgent,
        /** A FAIL that comes only from these agents is a data gap: human review instead of decline. */
        @DefaultValue("intake") List<String> dataGapAgents,
        @DefaultValue({"race", "religion", "gender", "marital status", "national origin", "ethnicity"})
        List<String> prohibitedTerms) {

    public static LoanPolicyProperties defaults() {
        return new LoanPolicyProperties("loan-policy-2026.1", new BigDecimal("6.5"), BigDecimal.TEN,
                new BigDecimal("36.0"), new BigDecimal("0.05"), 2, 60, "max", new BigDecimal("100000"), 84,
                "kyc", List.of("intake"),
                List.of("race", "religion", "gender", "marital status", "national origin", "ethnicity"));
    }
}
