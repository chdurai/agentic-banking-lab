package io.github.chdurai.agenticbanking.reflection;

import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.policy.LoanPolicyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reflection loop: critiques the draft decision against the evidence. Catches
 * contradictions (approved despite FAIL, missing adverse-action reasons, pricing
 * math errors) before the decision ships.
 */
@Component
public class CriticAgent {

    private static final Logger log = LoggerFactory.getLogger(CriticAgent.class);

    private static final String SYSTEM_TEMPLATE = """
            You are an independent quality-assurance reviewer for loan decisions. Check the
            draft decision against the agent reports:
            1. Consistency: APPROVED must not coexist with any FAIL report; DECLINED must cite
               specific adverse-action reasons tied to actual findings.
            2. Pricing: interestRateApr should approximately equal %s + (average riskScore / %s), capped at %s.
            3. Fairness: reasoning must not reference prohibited bases (race, religion, sex,
               marital status, national origin, age).
            4. Completeness: MANUAL_REVIEW must list what a human must verify.
            Set consistent=true only if ALL checks pass. suggestedDecision is your recommended
            final decision (APPROVED/DECLINED/MANUAL_REVIEW) if inconsistent, else repeat the draft.
            """;

    public record CritiqueReport(boolean consistent, List<String> issues,
                                 String suggestedDecision, String explanation) {}

    private final LlmGateway llm;
    private final String system;

    public CriticAgent(LlmGateway llm, LoanPolicyProperties policy,
                       io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities) {
        this.llm = llm;
        this.capabilities = capabilities;
        this.system = SYSTEM_TEMPLATE.formatted(policy.aprBase().toPlainString(),
                policy.aprRiskDivisor().toPlainString(), policy.aprCap().toPlainString());
    }

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;

    public CritiqueReport critique(LoanContext context, LoanDecision draft) {
        try (var scope = capabilities.enter("critic", context.application().applicantId())) {
            return critiqueScoped(context, draft);
        }
    }

    private CritiqueReport critiqueScoped(LoanContext context, LoanDecision draft) {
        capabilities.checkView("loan-application");
        String user = """
                Draft decision: %s, amount=%s, apr=%s
                Adverse-action reasons: %s
                Conditions: %s
                Reasoning: %s

                Evidence (agent reports):
                %s
                """.formatted(draft.decision(), draft.approvedAmount(), draft.interestRateApr(),
                draft.adverseActionReasons(), draft.conditions(), context.untrusted(draft.reasoning()),
                context.priorFindings("critic"));

        CritiqueReport report = llm.call(AgentCallSpec.of("critic", system, user, CritiqueReport.class, 0.0)).value();
        log.info("[critic] consistent={} issues={}", report.consistent(), report.issues() == null ? 0 : report.issues().size());
        return report;
    }
}
