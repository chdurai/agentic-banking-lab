package io.github.chdurai.agenticbanking.policy;

import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.model.LoanDecision.Decision;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Produces the final decision: the stricter of policy and LLM, with policy-owned numbers. */
@Component
public class PolicyMerge {

    public LoanDecision merge(LoanDecision draft, PolicyEnvelope env, List<InvariantChecker.Violation> unresolved,
                              boolean criticUnresolved, String applicationId, List<AgentReport> reports) {
        boolean prohibited = unresolved.stream().anyMatch(v -> v.code().equals("PROHIBITED_BASIS"));
        boolean belowFloor = draft.decision().strictness() < env.floor().strictness();
        List<String> internal = new ArrayList<>(env.internalReasonCodes());
        unresolved.forEach(v -> internal.add(v.code()));

        Decision decision = draft.decision();
        List<String> conditions = copy(draft.conditions());
        List<String> adverse = copy(draft.adverseActionReasons());
        String reasoning = draft.reasoning();

        if (belowFloor) {
            decision = env.floor();
            reasoning = "Policy " + env.policyVersion() + " requires " + decision + ". " + nz(reasoning);
            adverse = new ArrayList<>(env.customerReasonCodes());
        }
        if (prohibited || criticUnresolved) {
            decision = stricter(decision, Decision.MANUAL_REVIEW);
            internal.add(prohibited ? "PROHIBITED_BASIS_UNRESOLVED" : "CRITIC_UNRESOLVED");
            if (prohibited) {
                reasoning = "Draft rationale withheld pending human review.";
                adverse = new ArrayList<>();
            }
        }

        BigDecimal amount = draft.approvedAmount();
        BigDecimal apr = draft.interestRateApr();
        if (decision == Decision.APPROVED) {
            if (amount == null || amount.compareTo(env.maxAmount()) > 0) {
                amount = env.maxAmount();
            }
            apr = env.apr();
        } else {
            amount = BigDecimal.ZERO;
            apr = null;
        }
        if (decision == Decision.DECLINED) {
            conditions = new ArrayList<>();
            if (adverse.stream().allMatch(s -> s == null || s.isBlank())) {
                adverse = new ArrayList<>(env.customerReasonCodes().isEmpty()
                        ? List.of("Application did not meet our underwriting criteria") : env.customerReasonCodes());
            }
        }
        if (decision == Decision.MANUAL_REVIEW && conditions.isEmpty()) {
            conditions.add("Underwriter must verify: " + String.join(", ", env.internalReasonCodes().isEmpty()
                    ? List.of("overall file consistency") : env.internalReasonCodes()));
        }

        List<String> customerCodes = decision == Decision.DECLINED ? env.customerReasonCodes() : List.of();
        return new LoanDecision(applicationId, decision, amount, apr, conditions, adverse, reasoning,
                reports, draft.revisionCount(), Instant.now(), env.policyVersion(),
                List.copyOf(internal), customerCodes);
    }

    /** Internal decision for a compliance hold, produced without calling any LLM. */
    public LoanDecision hold(PolicyEnvelope env, String applicationId, List<AgentReport> reports) {
        return new LoanDecision(applicationId, Decision.COMPLIANCE_HOLD, BigDecimal.ZERO, null,
                List.of("Compliance officer review required"), List.of(),
                "Restricted compliance route: " + String.join(", ", env.internalReasonCodes()),
                reports, 0, Instant.now(), env.policyVersion(), env.internalReasonCodes(), List.of());
    }

    private static Decision stricter(Decision a, Decision b) {
        return a.strictness() >= b.strictness() ? a : b;
    }

    private static List<String> copy(List<String> in) {
        return in == null ? new ArrayList<>() : new ArrayList<>(in);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
