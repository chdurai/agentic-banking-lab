package io.github.chdurai.agenticbanking.flow.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.agents.DecisionAgent;
import io.github.chdurai.agenticbanking.flow.FlowKit;
import io.github.chdurai.agenticbanking.flow.FlowProperties;
import io.github.chdurai.agenticbanking.flow.prompt.PromptBuilder;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecRegistry;
import io.github.chdurai.agenticbanking.flow.state.Finding;
import io.github.chdurai.agenticbanking.flow.state.FindingMapper;
import io.github.chdurai.agenticbanking.flow.state.Provenance;
import io.github.chdurai.agenticbanking.guardrails.InputGuardrail;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanContext;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.policy.InvariantChecker;
import io.github.chdurai.agenticbanking.policy.LoanPolicyProperties;
import io.github.chdurai.agenticbanking.policy.PolicyEnvelope;
import io.github.chdurai.agenticbanking.reflection.CriticAgent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Protocol-control evidence, not an inference about a model's willingness or ability to correct facts. */
class TargetedChallengeEvaluationTest {
    private static final Instant TIME = Instant.parse("2026-06-17T12:00:00Z");
    record Arm(String termination, int rounds, int ownerCalls, int decisionCalls,
               int openIssues, int rebuttals, boolean needsHuman, boolean safelyResolvedInOneRound) {}
    record Pair(String id, String response, int conflictingFacts, Arm decisionOnlyControl, Arm targeted) {}
    record Metrics(int cases, long safelyResolvedInOneRound, double rate) {}
    record Report(String schemaVersion, String mode, Metrics decisionOnlyControl, Metrics targeted,
                  Metrics correctedResponsesOnly, boolean syntheticEightyPercentTargetMet,
                  Double liveFreeTextComparisonRate, List<Pair> observations, List<String> limitations) {}

    @Test void pairedChallengesMeasureResolutionAndPreserveHumanReviewForUnchangedDroppedAndUnavailableEvidence() throws Exception {
        List<Pair> pairs = new ArrayList<>();
        var shapes = List.of(Map.<String, Object>of("employer", "Synthetic Employer"),
                Map.<String, Object>of("annualIncome", 145000),
                Map.<String, Object>of("employer", "Synthetic Employer", "annualIncome", 145000));
        int id = 0;
        for (var facts : shapes) {
            for (String response : List.of("corrected", "unchanged", "dropped", "unavailable")) {
                pairs.add(new Pair("challenge-" + id++, response, facts.size(),
                        execute(facts, response, false, true), execute(facts, response, true, true)));
            }
            for (boolean targeted : List.of(false, true)) {
                Arm clean = execute(facts, "corrected", targeted, false);
                assertThat(clean.rounds()).isZero();
                assertThat(clean.ownerCalls()).isZero();
                assertThat(clean.needsHuman()).isFalse();
            }
        }
        var control = metrics(pairs.stream().map(Pair::decisionOnlyControl).toList());
        var targeted = metrics(pairs.stream().map(Pair::targeted).toList());
        var corrected = metrics(pairs.stream().filter(p -> p.response().equals("corrected")).map(Pair::targeted).toList());
        var report = new Report("targeted-challenge-report-v1", "SCRIPTED_PROTOCOL_CONTROL", control, targeted, corrected,
                targeted.rate() >= .8, null, List.copyOf(pairs), List.of(
                "Both arms execute the real coordinator, detector, invariants, typed state and redraft bounds.",
                "The decision-only control holds owner evidence unchanged while redrafting; it is not a live free-text model run.",
                "Decision/critic outputs and owner corrections are scripted; response frequencies are a balanced test matrix, not production prevalence.",
                "Only conflicted cases enter the one-round denominator; clean cases are checked separately and cannot inflate convergence.",
                "Resolution requires no open issue, no rebuttal and no human gate, not merely a RESOLVED termination.",
                "Live H7 convergence, relative free-text quality and independent judge calibration remain unmeasured."));
        Files.createDirectories(Path.of("target"));
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/targeted-challenge-report.json").toFile(), report);
        assertThat(report.targeted()).isEqualTo(new Metrics(12, 3, .25));
        assertThat(report.decisionOnlyControl()).isEqualTo(new Metrics(12, 0, 0));
        assertThat(report.correctedResponsesOnly()).isEqualTo(new Metrics(3, 3, 1));
        assertThat(report.syntheticEightyPercentTargetMet()).isFalse();
        assertThat(report.liveFreeTextComparisonRate()).isNull();
        assertThat(pairs).allSatisfy(pair -> {
            assertThat(pair.targeted().ownerCalls()).isEqualTo(1);
            assertThat(pair.targeted().decisionCalls()).isEqualTo(2);
            assertThat(pair.targeted().needsHuman()).isEqualTo(!pair.response().equals("corrected"));
            assertThat(pair.decisionOnlyControl().needsHuman()).isTrue();
        });
    }

    private Metrics metrics(List<Arm> arms) {
        long resolved = arms.stream().filter(Arm::safelyResolvedInOneRound).count();
        return new Metrics(arms.size(), resolved, (double) resolved / arms.size());
    }

    private Arm execute(Map<String, Object> correct, String response, boolean targeted, boolean conflicted) {
        var props = new FlowProperties();
        props.setState("v2");
        var kit = new FlowKit(props, new FindingMapper(new InputGuardrail()), new PromptBuilder(props));
        var context = new LoanContext(new LoanApplication("CHALLENGE", "Synthetic Applicant", "4321",
                new BigDecimal("145000"), 8, new BigDecimal("20000"), "home renovation", 60,
                new BigDecimal("1200"), "Synthetic Employer", List.of("synthetic.pdf")), kit);
        var report = new AgentReport("credit-risk", AgentReport.Status.PASS, 20, "Evidence reviewed", List.of(), List.of());
        context.addReport(report);
        context.state().append(fact("kyc", correct));
        var wrong = new java.util.LinkedHashMap<String, Object>();
        correct.forEach((key, value) -> wrong.put(key, value instanceof Number ? 95000 : "Other Synthetic Employer"));
        context.state().append(fact("credit-risk", conflicted ? wrong : correct));
        var candidate = new LoanDecision("CHALLENGE", LoanDecision.Decision.APPROVED,
                new BigDecimal("20000"), new BigDecimal("7.1"), List.of(), List.of(),
                "Synthetic decision", List.of(report), 0, TIME);
        var envelope = new PolicyEnvelope(LoanDecision.Decision.APPROVED,
                new BigDecimal("35000"), new BigDecimal("7.1"), List.of(), List.of(), "test-policy");
        var decision = mock(DecisionAgent.class);
        var critic = mock(CriticAgent.class);
        var decisionCalls = new AtomicInteger();
        var ownerCalls = new AtomicInteger();
        when(decision.decide(any(), anyString())).thenAnswer(invocation -> { decisionCalls.incrementAndGet(); return candidate; });
        when(decision.decide(any(), anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> { decisionCalls.incrementAndGet(); return candidate; });
        when(critic.critique(any(), any())).thenReturn(new CriticAgent.CritiqueReport(true, List.of(), "APPROVED", "scripted"));
        var coordinator = new ChallengeCoordinator(decision, critic, new ContradictionDetector(),
                new InvariantChecker(LoanPolicyProperties.defaults()), AgentSpecRegistry.withDefaults(), props);
        var outcome = coordinator.run(new ChallengeCoordinator.Request(context, "", () -> envelope, (owner, claim) -> {
            assertThat(owner).isEqualTo("credit-risk");
            correct.keySet().forEach(key -> assertThat(claim).contains(key));
            if (!targeted) return Optional.of(report);
            ownerCalls.incrementAndGet();
            if (response.equals("unavailable")) return Optional.empty();
            if (response.equals("corrected")) context.state().append(fact(owner, correct));
            if (response.equals("dropped")) context.state().append(fact(owner, Map.of()));
            return Optional.of(report);
        }));
        for (var type : List.of(io.github.chdurai.agenticbanking.flow.state.AgentMessage.Type.CHALLENGE,
                io.github.chdurai.agenticbanking.flow.state.AgentMessage.Type.CHALLENGE_RESPONSE)) {
            assertThat(context.state().messages().stream().filter(message -> message.type() == type).count())
                    .isEqualTo(conflicted ? correct.size() : 0);
        }
        boolean safelyResolved = outcome.termination() == ChallengeCoordinator.Termination.RESOLVED
                && !outcome.needsHuman(2) && outcome.rounds() <= 1;
        return new Arm(outcome.termination().name(), outcome.rounds(), ownerCalls.get(), decisionCalls.get(),
                outcome.open().size(), outcome.rebutted().size(), outcome.needsHuman(2), safelyResolved);
    }

    private Finding fact(String agent, Map<String, Object> facts) {
        return new Finding("fact-" + agent + "-" + facts.hashCode(), "CHALLENGE", "loan", agent, Finding.Kind.CHECK_RESULT,
                Finding.Status.PASS, facts, List.of(), "Synthetic evidence", List.of(), List.of(), "HIGH", null,
                Provenance.of("scripted-evaluation", false, 0), false, Finding.SCHEMA_VERSION, TIME);
    }
}
