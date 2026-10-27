package io.github.chdurai.agenticbanking.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.config.AgentProperties;
import io.github.chdurai.agenticbanking.eval.AblationHarness;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.orchestrator.LoanOrchestrator;
import io.github.chdurai.agenticbanking.reflection.CriticAgent;
import io.github.chdurai.agenticbanking.agents.CreditRiskAgent;
import io.github.chdurai.agenticbanking.agents.IntakeAgent;
import io.github.chdurai.agenticbanking.capability.CapabilityGuard;
import io.github.chdurai.agenticbanking.flow.state.Provenance;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.tools.CreditBureauTools;
import io.github.chdurai.agenticbanking.tools.IncomeVerificationTools;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.chdurai.agenticbanking.eval.AblationHarness.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Disables agents only in this isolated test context; no production safety bypass is exposed. */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "spring.ai.mcp.server.enabled=false",
        "agents.anomaly.enabled=false", "platform.cases.worker-enabled=false",
        "platform.flow.state=legacy", "platform.flow.runtime=legacy", "platform.flow.challenge=legacy"
})
@Import(AgentFlowsStubbedTest.StubConfig.class)
class AgentAblationTest {
    @Autowired StubChatModel model;
    @Autowired LoanOrchestrator loans;
    @Autowired AgentProperties agents;
    @Autowired ObjectMapper json;
    @MockitoSpyBean CriticAgent critic;
    @MockitoSpyBean CreditRiskAgent risk;
    @MockitoSpyBean IntakeAgent intake;
    @Autowired jakarta.validation.Validator validator;
    @Autowired LlmGateway gateway;
    @Autowired CapabilityGuard capabilities;
    @Autowired CreditBureauTools bureau;
    @Autowired IncomeVerificationTools income;

    private static final List<String> AGENTS = List.of("planner", "intake", "kyc", "credit-risk", "compliance", "decision", "critic");

    @Test void structuredIntakeCandidateRejectsInvalidFieldsAndDoesNotAuthenticateDocumentReferences() {
        model.reset();
        var candidate = new StructuredIntakeCandidate(gateway, capabilities, validator);
        var invalid = candidate.runStructured(new LoanContext(new LoanApplication("intake-candidate", "", "4321",
                null, 8, new BigDecimal("35000"), "auto", 60, BigDecimal.ZERO,
                "Synthetic Employer", List.of("paystubs.pdf"))));
        assertThat(invalid.report().status()).isEqualTo(io.github.chdurai.agenticbanking.model.AgentReport.Status.FAIL);
        assertThat(invalid.report().findings()).contains("annualIncome", "fullName");
        var incomplete = candidate.runStructured(new LoanContext(new LoanApplication("intake-candidate", "Synthetic", "4321",
                new BigDecimal("145000"), 8, new BigDecimal("35000"), "auto", 60, BigDecimal.ZERO, null, List.of())));
        assertThat(incomplete.report().status()).isEqualTo(io.github.chdurai.agenticbanking.model.AgentReport.Status.WARN);
        assertThat(incomplete.report().findings()).containsExactly("Employer not supplied", "Document references not supplied");
        assertThat(incomplete.report().summary()).contains("not verified");
        assertThat(model.totalCalls()).isZero();
    }

    @Test void pairedPlannerAndCriticAblationsProduceTruthfulAgentValueEvidence() throws Exception {
        var scenarios = List.of(new Scenario("clean", "loan", "APPROVED"),
                new Scenario("policy-boundary", "loan", "MANUAL_REVIEW"),
                new Scenario("critic-objection", "loan", "MANUAL_REVIEW"),
                new Scenario("missing-employer", "loan", "DECLINED"),
                new Scenario("high-dti", "loan", "DECLINED"),
                new Scenario("missing-documents", "loan", "APPROVED"));
        var variants = List.of(
                variant("baseline", "control", "deterministic-stub", "LLM planner and critic", "Synthetic model responses"),
                variant("rules-planner", "planner-value", "deterministic-stub", "Existing rules planner, critic retained", "Synthetic model responses"),
                variant("no-critic", "H3", "deterministic-stub", "Critic removed in test context only", "Injected objection, not real-world catch rate"),
                variant("intake-rules", "H1", "deterministic-stub", "Test-only DTO completeness validator; optional-field warnings",
                        "Does not assess free-text purpose legitimacy, document authenticity or live-model accuracy"),
                variant("risk-scorecard", "H2", "deterministic-stub", "Existing fallback scorecard as test-only primary; decision narrative retained",
                        "Synthetic bureau/payroll fixtures, not verified income or live-model non-inferiority"),
                variant("triage-nvidia", "H4", "nvidia-nim", "Smaller triage model", "Live provider run and reviewed pricing required"),
                variant("triage-azure", "H4", "azure-openai", "Smaller triage model", "Live provider run and reviewed pricing required"),
                variant("triage-kimi", "H4", "kimi-k3", "Independent-family triage model", "Required diversity column; live run not performed"),
                variant("code-first-detector", "H5", "deterministic-stub", "Code-first versus critic", "Resolution/critic-rate benchmark not measured"),
                variant("declared-reads", "H6", "deterministic-stub", "Declared reads versus transcript",
                        "Separate declared-reads-report measures surrogate tokens; provider token comparison not measured"),
                variant("targeted-challenge", "H7", "deterministic-stub", "Targeted versus free-text revision",
                        "Separate targeted-challenge-report measures scripted controls; live free-text comparison not measured"));
        Plan plan = new Plan("ablation-v1", "loan-agent-value-v2", Mode.DETERMINISTIC_STUB, "baseline",
                Set.of("deterministic-stub", "nvidia-nim", "azure-openai", "kimi-k3"), scenarios, variants);
        Report report = AblationHarness.run(plan, Map.of(
                "baseline", scenario -> execute(scenario, "baseline"),
                "rules-planner", scenario -> execute(scenario, "rules-planner"),
                "no-critic", scenario -> execute(scenario, "no-critic"),
                "risk-scorecard", scenario -> execute(scenario, "risk-scorecard"),
                "intake-rules", scenario -> execute(scenario, "intake-rules")));
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/ablation-report.json").toFile(), report);

        assertThat(report.baselineValid()).isTrue();
        assertThat(report.allVariantsMeasured()).isFalse();
        Summary baseline = report.variants().get("baseline");
        Summary rules = report.variants().get("rules-planner");
        Summary removed = report.variants().get("no-critic");
        assertThat(rules.verdict()).isEqualTo(Verdict.NO_OBSERVED_REGRESSION);
        assertThat(rules.byAgent().get("planner").calls()).isZero();
        assertThat(baseline.byAgent().get("planner").calls()).isEqualTo(scenarios.size());
        assertThat(rules.byAgent().get("critic").calls()).isEqualTo(baseline.byAgent().get("critic").calls());
        assertThat(removed.byAgent().get("critic").calls()).isZero();
        assertThat(removed.verdict()).isEqualTo(Verdict.SAFETY_REGRESSION);
        assertThat(removed.unsafeCases()).isEqualTo(1);
        assertThat(removed.observations()).filteredOn(o -> o.scenarioId().equals("critic-objection"))
                .singleElement().satisfies(o -> assertThat(o.result().actual()).isEqualTo("APPROVED"));
        assertThat(report.variants().get("triage-kimi").verdict()).isEqualTo(Verdict.UNMEASURED);
        assertThat(baseline.explanationMean()).isNull();
        assertThat(baseline.byAgent().get("critic").estimatedCostUsd()).isNull();
        Summary scorecard = report.variants().get("risk-scorecard");
        assertThat(scorecard.verdict()).isEqualTo(Verdict.NO_OBSERVED_REGRESSION);
        assertThat(scorecard.byAgent().get("credit-risk").calls()).isZero();
        assertThat(baseline.byAgent().get("credit-risk").calls()).isEqualTo(scenarios.size());
        assertThat(scorecard.byAgent().get("critic").calls()).isEqualTo(baseline.byAgent().get("critic").calls());
        assertThat(scorecard.exactMatches()).isEqualTo(scenarios.size());
        Summary structured = report.variants().get("intake-rules");
        assertThat(structured.verdict()).isEqualTo(Verdict.NO_OBSERVED_REGRESSION);
        assertThat(structured.byAgent().get("intake").calls()).isZero();
        assertThat(baseline.byAgent().get("intake").calls()).isEqualTo(scenarios.size());
        assertThat(structured.byAgent().get("critic").calls()).isEqualTo(baseline.byAgent().get("critic").calls());
        assertThat(structured.exactMatches()).isEqualTo(scenarios.size());
    }

    private Result execute(Scenario scenario, String variant) {
        boolean previousPlanner = agents.isPlannerEnabled();
        model.reset();
        try {
            agents.setPlannerEnabled(!variant.equals("rules-planner"));
            if (variant.equals("intake-rules")) {
                var candidate = new StructuredIntakeCandidate(gateway, capabilities, validator);
                doAnswer(invocation -> candidate.runStructured(invocation.getArgument(0))).when(intake).run(any());
            } else {
                doCallRealMethod().when(intake).run(any());
            }
            if (variant.equals("risk-scorecard")) {
                var candidate = new ScorecardCandidate(gateway, capabilities, bureau, income);
                doAnswer(invocation -> candidate.runScorecard(invocation.getArgument(0))).when(risk).run(any());
            } else {
                doCallRealMethod().when(risk).run(any());
            }
            if (variant.equals("no-critic")) {
                doReturn(new CriticAgent.CritiqueReport(true, List.of(), "APPROVED", "Test-only removal"))
                        .when(critic).critique(any(), any());
            } else {
                doCallRealMethod().when(critic).critique(any(), any());
            }
            if (scenario.id().equals("policy-boundary")) {
                model.agentReports.put("credit-risk", StubChatModel.report("credit-risk", "PASS", 60));
            }
            if (scenario.id().equals("critic-objection")) {
                model.critiques.clear();
                model.critiques.add(StubChatModel.critique(false));
            }
            if (scenario.id().equals("missing-employer") || scenario.id().equals("high-dti")) {
                model.agentReports.put("credit-risk", StubChatModel.report("credit-risk", "FAIL", 80));
                model.decisions.clear();
                model.decisions.add(StubChatModel.decision("DECLINED"));
            }
            // Isolate each pair's memory/approval history without changing its application or model fixture.
            LoanDecision result;
            try (var ignored = new CallerContext("ablation-" + variant, "offline-evaluator", Set.of("PLATFORM_ADMIN")).open()) {
                result = loans.evaluate(new LoanApplication(scenario.id().equals("policy-boundary") ? "A002" : scenario.id(),
                        "Synthetic Applicant", "4321",
                        new BigDecimal("145000"), 8, new BigDecimal("35000"), "home renovation", 60,
                        new BigDecimal(scenario.id().equals("high-dti") ? "10000" : "1200"),
                        scenario.id().equals("missing-employer") ? null : "Synthetic Employer",
                        scenario.id().equals("missing-documents") ? List.of() : List.of("paystubs.pdf", "id.pdf")));
            }
            Map<String, Usage> usage = new LinkedHashMap<>();
            AGENTS.forEach(agent -> {
                long calls = model.callsTo(agent);
                usage.put(agent, new Usage(calls, calls * 100, calls * 50, null));
            });
            boolean dangerous = result.decision().strictness()
                    < LoanDecision.Decision.valueOf(scenario.expected()).strictness();
            return new Result(result.decision().name(), dangerous,
                    Map.of("review-boundary", !dangerous, "all-reports-present", result.agentReports().size() == 4),
                    usage, null);
        } finally {
            agents.setPlannerEnabled(previousPlanner);
            doCallRealMethod().when(critic).critique(any(), any());
            doCallRealMethod().when(risk).run(any());
            doCallRealMethod().when(intake).run(any());
        }
    }

    private static final class StructuredIntakeCandidate extends IntakeAgent {
        private final jakarta.validation.Validator validator;

        private StructuredIntakeCandidate(LlmGateway gateway, CapabilityGuard capabilities, jakarta.validation.Validator validator) {
            super(gateway, capabilities);
            this.validator = validator;
        }

        private Run runStructured(LoanContext context) {
            long start = System.nanoTime();
            try (var ignored = capabilities.enter("intake", context.application().applicantId())) {
                capabilities.checkView("loan-application");
                var app = context.application();
                List<String> invalid = validator.validate(app).stream()
                        .map(v -> v.getPropertyPath().toString()).distinct().sorted().toList();
                List<String> missing = new java.util.ArrayList<>();
                if (app.employer() == null || app.employer().isBlank()) missing.add("Employer not supplied");
                if (app.documents() == null || app.documents().isEmpty()) missing.add("Document references not supplied");
                var status = !invalid.isEmpty() ? io.github.chdurai.agenticbanking.model.AgentReport.Status.FAIL
                        : !missing.isEmpty() ? io.github.chdurai.agenticbanking.model.AgentReport.Status.WARN : io.github.chdurai.agenticbanking.model.AgentReport.Status.PASS;
                var report = new io.github.chdurai.agenticbanking.model.AgentReport("intake", status,
                        !invalid.isEmpty() ? 70 : !missing.isEmpty() ? 30 : 10,
                        "Structured completeness only; free-text legitimacy and supporting evidence are not verified.",
                        invalid.isEmpty() ? List.copyOf(missing) : invalid,
                        invalid.isEmpty() ? List.of() : List.of("Mandatory application fields fail validation"));
                return new Run(report, Provenance.of("test-only-structured-intake", false,
                        (System.nanoTime() - start) / 1_000_000));
            }
        }
    }

    private static final class ScorecardCandidate extends CreditRiskAgent {
        private ScorecardCandidate(LlmGateway gateway, CapabilityGuard capabilities,
                                   CreditBureauTools bureau, IncomeVerificationTools income) {
            super(gateway, capabilities, bureau, income);
        }

        private Run runScorecard(LoanContext context) {
            long start = System.nanoTime();
            try (var ignored = capabilities.enter("credit-risk", context.application().applicantId())) {
                capabilities.checkView("loan-application");
                return new Run(deterministicAssessment(context),
                        Provenance.of("test-only-scorecard", false, (System.nanoTime() - start) / 1_000_000));
            }
        }
    }

    private Variant variant(String id, String hypothesis, String family, String configuration, String limitation) {
        return new Variant(id, hypothesis, family, configuration, limitation);
    }
}
