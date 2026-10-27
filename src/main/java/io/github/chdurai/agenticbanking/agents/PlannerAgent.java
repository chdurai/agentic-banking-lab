package io.github.chdurai.agenticbanking.agents;

import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.llm.LlmResult;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dynamic planner: inspects the application and proposes which agents run and in
 * what order (parallel stages). The orchestrator validates the plan against the
 * registry and falls back to the static plan if the output is unusable.
 */
@Component
public class PlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    private static final String SYSTEM = """
            You are the orchestration planner for a bank's loan pipeline. Available agents:
            - "intake": validates completeness/consistency (always required, cheap)
            - "kyc": sanctions/PEP screening via tools (always recommended - regulatory)
            - "credit-risk": bureau + income verification, default-risk scoring
            - "compliance": policy/regulatory review with retrieved policy clauses

            Output a plan as stages; agents in the same stage run in parallel, so only
            group agents that do not depend on each other's findings. kyc and credit-risk
            are independent. compliance benefits from seeing all prior findings, so it
            usually goes last. You may drop credit-risk only for trivially ineligible
            applications (e.g. amount over policy cap). Never drop intake.
            """;

    public record PlanOutput(List<List<String>> stages, String rationale) {}

    private final LlmGateway llm;

    public PlannerAgent(LlmGateway llm, io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities) {
        this.llm = llm;
        this.capabilities = capabilities;
    }

    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;

    public PlanOutput plan(LoanApplication a, String memorySummary) {
        try (var scope = capabilities.enter("planner", a.applicantId())) {
            return planScoped(a, memorySummary);
        }
    }

    private PlanOutput planScoped(LoanApplication a, String memorySummary) {
        capabilities.checkView("loan-application");
        String user = """
                Plan the pipeline for this application:
                - requestedAmount: %s, termMonths: %d, purpose: %s
                - annualIncome: %s, employmentYears: %d, existingMonthlyDebt: %s
                - %s
                """.formatted(a.requestedAmount(), a.termMonths(), a.loanPurpose(),
                a.annualIncome(), a.employmentYears(), a.existingMonthlyDebt(), memorySummary);

        LlmResult<PlanOutput> result = llm.call(AgentCallSpec.of("planner", SYSTEM, user, PlanOutput.class, 0.0));
        PlanOutput output = result.value();
        log.info("[planner] model={} stages={} rationale={}", result.model(), output.stages(), output.rationale());
        return output;
    }

}
