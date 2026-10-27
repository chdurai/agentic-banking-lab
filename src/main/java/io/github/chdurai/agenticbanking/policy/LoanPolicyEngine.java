package io.github.chdurai.agenticbanking.policy;

import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision.Decision;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Policy-as-code for loan decisions. A pure function of (application, agent reports, properties):
 * the LLM argues the case, but it can only be as lenient as this envelope allows.
 */
@Component
public class LoanPolicyEngine {

    private static final Map<String, String> CUSTOMER_REASONS = Map.of(
            "credit-risk", "Credit risk assessment did not meet our lending criteria",
            "intake", "Application information was incomplete or inconsistent",
            "compliance", "Application does not meet our lending policy requirements");
    private static final String DEFAULT_CUSTOMER_REASON = "Application did not meet our underwriting criteria";

    private final LoanPolicyProperties props;

    public LoanPolicyEngine(LoanPolicyProperties props) {
        this.props = props;
    }

    public LoanPolicyProperties properties() {
        return props;
    }

    public PolicyEnvelope evaluate(LoanApplication application, List<AgentReport> reports) {
        Decision floor = Decision.APPROVED;
        Set<String> internal = new LinkedHashSet<>();
        Set<String> customer = new LinkedHashSet<>();

        if (reports.isEmpty()) {
            floor = stricter(floor, Decision.MANUAL_REVIEW);
            internal.add("NO_AGENT_REPORTS");
        }

        List<AgentReport> fails = reports.stream().filter(AgentReport::failed).toList();
        boolean sanctions = fails.stream().anyMatch(this::isSanctionsHit);
        if (sanctions) {
            floor = Decision.COMPLIANCE_HOLD;
            internal.add("SANCTIONS_MATCH");
        }
        for (AgentReport f : fails) {
            if (isSanctionsHit(f)) {
                continue;
            }
            boolean dataGap = props.dataGapAgents().contains(f.agentName());
            floor = stricter(floor, dataGap ? Decision.MANUAL_REVIEW : Decision.DECLINED);
            internal.add((dataGap ? "DATA_GAP_" : "FAIL_") + code(f.agentName()));
            if (!dataGap) {
                customer.add(CUSTOMER_REASONS.getOrDefault(f.agentName(), DEFAULT_CUSTOMER_REASON));
            }
        }

        for (AgentReport u : reports) {
            if (u.status() == AgentReport.Status.UNAVAILABLE) {
                floor = stricter(floor, Decision.MANUAL_REVIEW);
                internal.add("UNAVAILABLE_" + code(u.agentName()));
            }
        }

        long warns = reports.stream().filter(r -> r.status() == AgentReport.Status.WARN).count();
        if (warns >= props.warnReviewCount()) {
            floor = stricter(floor, Decision.MANUAL_REVIEW);
            internal.add("WARN_COUNT_" + warns);
        }
        if (riskMetric(reports) >= props.riskReviewThreshold()) {
            floor = stricter(floor, Decision.MANUAL_REVIEW);
            internal.add("RISK_SCORE_AT_OR_ABOVE_" + props.riskReviewThreshold());
        }

        BigDecimal cap = application.requestedAmount().min(props.maxAmount());
        if (application.requestedAmount().compareTo(props.maxAmount()) > 0) {
            internal.add("AMOUNT_CAPPED_AT_" + props.maxAmount().toPlainString());
        }
        if (application.termMonths() > props.maxTermMonths()) {
            floor = stricter(floor, Decision.DECLINED);
            internal.add("TERM_ABOVE_" + props.maxTermMonths());
            customer.add(CUSTOMER_REASONS.get("compliance"));
        }
        return new PolicyEnvelope(floor, cap, apr(reports), List.copyOf(internal),
                floor == Decision.COMPLIANCE_HOLD ? List.of() : List.copyOf(customer), props.version());
    }

    /** base + avgRisk/divisor, capped and rounded to 2 decimals. */
    public BigDecimal apr(List<AgentReport> reports) {
        double avg = reports.stream().mapToInt(AgentReport::riskScore).average().orElse(0);
        BigDecimal raw = props.aprBase().add(BigDecimal.valueOf(avg)
                .divide(props.aprRiskDivisor(), 6, RoundingMode.HALF_UP));
        return raw.min(props.aprCap()).setScale(2, RoundingMode.HALF_UP);
    }

    private double riskMetric(List<AgentReport> reports) {
        var scores = reports.stream().mapToInt(AgentReport::riskScore);
        return "avg".equalsIgnoreCase(props.riskReviewMetric())
                ? scores.average().orElse(0) : scores.max().orElse(0);
    }

    private boolean isSanctionsHit(AgentReport r) {
        return r.failed() && (r.agentName().equals(props.sanctionsAgent())
                || (r.redFlags() != null && r.redFlags().stream()
                        .anyMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("sanction"))));
    }

    private static Decision stricter(Decision a, Decision b) {
        return a.strictness() >= b.strictness() ? a : b;
    }

    private static String code(String agent) {
        return agent.toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
