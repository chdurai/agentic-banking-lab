package io.github.chdurai.agenticbanking.agents;

import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.llm.LlmResult;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.policy.LoanPolicyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Final agent: synthesizes all upstream reports into a binding decision with
 * pricing. Supports revision: the orchestrator re-invokes it with critic feedback
 * when the reflection loop finds inconsistencies.
 */
@Component
public class DecisionAgent {

    private static final Logger log = LoggerFactory.getLogger(DecisionAgent.class);
    private static final String NAME = "decision";

    private static final String SYSTEM_TEMPLATE = """
            You are the chief credit officer making the final loan decision. Weigh all
            agent reports and any prior application history. Policy %s rules:
            - Any FAIL report => DECLINED (unless the fail is purely a data gap, then MANUAL_REVIEW).
              A sanctions match is never declined by you; it is handled by a restricted compliance route.
            - %d or more WARN reports, or any riskScore >= %d => MANUAL_REVIEW.
            - Otherwise APPROVED.
            - Pricing: base APR %s%% + (average agent riskScore / %s), capped at %s%%. Round to 2 decimals.
            - approvedAmount may be lower than requested when risk warrants it, never above %s.
            - On DECLINED you MUST populate adverseActionReasons with specific, factual
              principal reasons (ECOA/Reg B). Never generic ("insufficient score") - cite
              numbers, e.g. "FICO 648 below 660 threshold for requested amount".
            - conditions lists covenants (income docs, autopay) for approvals, or what a
              human must verify for MANUAL_REVIEW. Empty list for declines.
            - Never base reasoning on race, religion, sex, age, marital status or national origin.
            """;

    /** Structured output from the decision model. */
    public record FinalDecision(String decision, BigDecimal approvedAmount,
                                BigDecimal interestRateApr, List<String> conditions,
                                List<String> adverseActionReasons, String reasoning) {}

    private final LlmGateway llm;
    private final String system;

    public DecisionAgent(LlmGateway llm, LoanPolicyProperties policy,
                         io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities) {
        this.llm = llm;
        this.capabilities = capabilities;
        this.system = SYSTEM_TEMPLATE.formatted(policy.version(), policy.warnReviewCount(),
                policy.riskReviewThreshold(), policy.aprBase().toPlainString(),
                policy.aprRiskDivisor().toPlainString(), policy.aprCap().toPlainString(),
                policy.maxAmount().toPlainString());
    }

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;

    public LoanDecision decide(LoanContext context, String memorySummary) {
        return decide(context, memorySummary, null, 0);
    }

    public LoanDecision decide(LoanContext context, String memorySummary,
                               String criticFeedback, int revisionCount) {
        try (var scope = capabilities.enter(NAME, context.application().applicantId())) {
            return decideScoped(context, memorySummary, criticFeedback, revisionCount);
        }
    }

    private LoanDecision decideScoped(LoanContext context, String memorySummary,
                                      String criticFeedback, int revisionCount) {
        capabilities.checkView("loan-application");
        LoanApplication a = context.application();

        String user = """
                Application: applicantId=%s, requestedAmount=%s, termMonths=%d, purpose=%s
                Pipeline terminated early: %s %s
                Applicant history: %s

                Agent reports:
                %s
                %s
                Render the final decision.
                """.formatted(a.applicantId(), a.requestedAmount(), a.termMonths(), context.untrusted(a.loanPurpose()),
                context.isTerminated(), context.isTerminated() ? "(" + context.terminationReason() + ")" : "",
                memorySummary, context.priorFindings("decision"),
                criticFeedback == null ? ""
                        : "\nA quality reviewer rejected your previous draft. Fix these issues:\n"
                        + criticFeedback + "\n");

        LlmResult<FinalDecision> result = llm.call(AgentCallSpec.of(NAME, system, user, FinalDecision.class, 0.1));
        FinalDecision fd = result.value();
        log.info("[decision] model={} -> {} (revision {})", result.model(), fd.decision(), revisionCount);

        return new LoanDecision(
                a.applicantId(),
                parseDecision(fd.decision()),
                fd.approvedAmount(), fd.interestRateApr(),
                fd.conditions() == null ? List.of() : fd.conditions(),
                fd.adverseActionReasons() == null ? List.of() : fd.adverseActionReasons(),
                fd.reasoning(), context.reports(), revisionCount, Instant.now());
    }

    /** Unknown or missing model output never throws: it routes to a human. */
    static LoanDecision.Decision parseDecision(String raw) {
        if (raw != null) {
            try {
                return LoanDecision.Decision.valueOf(raw.trim().toUpperCase().replace(' ', '_'));
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        log.warn("[decision] unparseable decision '{}' -> MANUAL_REVIEW", raw);
        return LoanDecision.Decision.MANUAL_REVIEW;
    }
}
