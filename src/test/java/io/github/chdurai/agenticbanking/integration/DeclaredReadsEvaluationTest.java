package io.github.chdurai.agenticbanking.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.api.EncodingType;
import io.github.chdurai.agenticbanking.config.AgentProperties;
import io.github.chdurai.agenticbanking.flow.FlowProperties;
import io.github.chdurai.agenticbanking.flow.prompt.PromptBuilder;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.orchestrator.LoanOrchestrator;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Same real graph and typed renderer in both arms; only declared-read filtering changes. */
@SpringBootTest(properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "spring.ai.mcp.client.enabled=false", "spring.ai.mcp.server.enabled=false",
        "agents.anomaly.enabled=false", "platform.cases.worker-enabled=false",
        "platform.flow.state=v2", "platform.flow.runtime=dag", "platform.flow.challenge=legacy"
})
@Import(AgentFlowsStubbedTest.StubConfig.class)
class DeclaredReadsEvaluationTest {
    @Autowired StubChatModel model;
    @Autowired LoanOrchestrator loans;
    @Autowired FlowProperties properties;
    @Autowired AgentProperties agents;
    @Autowired ObjectMapper json;
    @MockitoSpyBean PromptBuilder prompts;
    private final JTokkitTokenCountEstimator tokenizer = new JTokkitTokenCountEstimator(EncodingType.CL100K_BASE);

    record Measurement(int calls, long estimatedInputTokens) {}
    record Arm(String decision, Map<String, Measurement> byAgent) {}
    record Pair(String scenario, String expected, Arm fullTypedState, Arm declaredReads) {}
    record AgentMetric(long fullEstimatedInputTokens, long scopedEstimatedInputTokens,
                       double reduction, boolean twentyFivePercentTargetMet) {}
    record Report(String schemaVersion, String mode, String tokenizer, int pairedCases,
                  Map<String, AgentMetric> downstreamAgents, boolean allDownstreamTargetsMet,
                  Long providerReportedInputTokens, List<Pair> observations, List<String> limitations) {}

    @Test void actualPairedPromptsMeasureScopingWithoutPaddingTranscriptsOrUsingStubUsageAsTokens() throws Exception {
        boolean planner = agents.isPlannerEnabled();
        List<Pair> pairs = new ArrayList<>();
        try {
            agents.setPlannerEnabled(false);
            for (String scenario : List.of("clean", "review-boundary", "failed-risk")) {
                String expected = switch (scenario) {
                    case "review-boundary" -> "MANUAL_REVIEW";
                    case "failed-risk" -> "DECLINED";
                    default -> "APPROVED";
                };
                Arm full = execute(scenario, false);
                Arm scoped = execute(scenario, true);
                assertThat(full.decision()).isEqualTo(expected);
                assertThat(scoped.decision()).isEqualTo(expected);
                assertThat(scoped.byAgent().keySet()).isEqualTo(full.byAgent().keySet());
                pairs.add(new Pair(scenario, expected, full, scoped));
            }
        } finally {
            agents.setPlannerEnabled(planner);
            doCallRealMethod().when(prompts).render(any(), any());
        }
        Map<String, AgentMetric> metrics = new LinkedHashMap<>();
        for (String agent : List.of("kyc", "credit-risk", "compliance", "decision", "critic")) {
            long full = pairs.stream().mapToLong(p -> p.fullTypedState().byAgent().get(agent).estimatedInputTokens()).sum();
            long scoped = pairs.stream().mapToLong(p -> p.declaredReads().byAgent().get(agent).estimatedInputTokens()).sum();
            assertThat(full).as("Observed input tokens for %s", agent).isPositive();
            double reduction = 1.0 - (double) scoped / full;
            metrics.put(agent, new AgentMetric(full, scoped, reduction, reduction >= .25));
        }
        var report = new Report("declared-reads-report-v1", "DETERMINISTIC_STUB", "CL100K_BASE message-text estimate",
                pairs.size(), Map.copyOf(metrics), metrics.values().stream().allMatch(AgentMetric::twentyFivePercentTargetMet),
                null, List.copyOf(pairs), List.of(
                "Real orchestrator, gateway system/schema/user messages and DAG snapshots; model outputs are synthetic.",
                "Both arms use identical typed-state rendering and safety fences; the control renders all available findings.",
                "No irrelevant findings or long narratives are injected to manufacture savings.",
                "CL100K_BASE is a reproducible surrogate, not the provider tokenizer, chat framing, billing or reported usage.",
                "The current graph often has only declared dependencies available; credit-risk does not consume prior findings.",
                "Equal synthetic outcomes do not establish live quality or statistical non-inferiority."));
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/declared-reads-report.json").toFile(), report);
        assertThat(report.allDownstreamTargetsMet()).isFalse();
        assertThat(report.providerReportedInputTokens()).isNull();
        assertThat(metrics.get("credit-risk").reduction()).isZero();
        assertThat(pairs).hasSize(3);
    }

    private Arm execute(String scenario, boolean scoped) {
        model.reset();
        if (scoped) doCallRealMethod().when(prompts).render(any(), any());
        else doAnswer(invocation -> new PromptBuilder(properties).render(invocation.getArgument(0), null))
                .when(prompts).render(any(), any());
        if (scenario.equals("review-boundary")) {
            model.agentReports.put("credit-risk", StubChatModel.report("credit-risk", "WARN", 60));
        } else if (scenario.equals("failed-risk")) {
            model.agentReports.put("credit-risk", StubChatModel.report("credit-risk", "FAIL", 80));
        }
        LoanDecision result;
        try (var ignored = new CallerContext("prompt-" + (scoped ? "scoped" : "full") + "-" + scenario,
                "offline-evaluator", Set.of("PLATFORM_ADMIN")).open()) {
            result = loans.evaluate(new LoanApplication("PROMPT-" + scenario, "Synthetic Applicant", "4321",
                    new BigDecimal("145000"), 8, new BigDecimal("35000"), "home renovation", 60,
                    new BigDecimal("1200"), "Synthetic Employer", List.of("synthetic.pdf")));
        }
        Map<String, Measurement> measured = new LinkedHashMap<>();
        List.of("intake", "kyc", "credit-risk", "compliance", "decision", "critic")
                .forEach(agent -> measured.put(agent, new Measurement(0, 0)));
        model.promptsByAgent.forEach((agent, calls) -> measured.put(agent, new Measurement(calls.size(),
                calls.stream().mapToLong(p -> p.getInstructions().stream()
                        .mapToLong(message -> tokenizer.estimate(message.getText())).sum()).sum())));
        return new Arm(result.decision().name(), Map.copyOf(measured));
    }
}
