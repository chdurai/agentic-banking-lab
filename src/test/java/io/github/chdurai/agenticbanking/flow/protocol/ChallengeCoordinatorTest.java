package io.github.chdurai.agenticbanking.flow.protocol;

import io.github.chdurai.agenticbanking.agents.DecisionAgent;
import io.github.chdurai.agenticbanking.flow.FlowKit;
import io.github.chdurai.agenticbanking.flow.FlowProperties;
import io.github.chdurai.agenticbanking.flow.prompt.PromptBuilder;
import io.github.chdurai.agenticbanking.flow.protocol.ChallengeCoordinator.Outcome;
import io.github.chdurai.agenticbanking.flow.protocol.ChallengeCoordinator.Termination;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecRegistry;
import io.github.chdurai.agenticbanking.flow.state.AgentMessage;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChallengeCoordinatorTest {

    private final DecisionAgent decisionAgent = mock(DecisionAgent.class);
    private final CriticAgent critic = mock(CriticAgent.class);
    private final FlowProperties props = new FlowProperties();
    private final FindingMapper mapper = new FindingMapper(new InputGuardrail());
    private final FlowKit kit = new FlowKit(props, mapper, new PromptBuilder(props));
    private final PolicyEnvelope envelope = new PolicyEnvelope(LoanDecision.Decision.APPROVED,
            new BigDecimal("35000"), new BigDecimal("7.1"), List.of(), List.of(), "p1");
    private ChallengeCoordinator coordinator;
    private LoanContext ctx;

    @BeforeEach
    void setUp() {
        props.setState("v2");
        coordinator = new ChallengeCoordinator(decisionAgent, critic, new ContradictionDetector(),
                new InvariantChecker(LoanPolicyProperties.defaults()), AgentSpecRegistry.withDefaults(), props);
        ctx = new LoanContext(new LoanApplication("A1", "Jane Cooper", "4321", new BigDecimal("100000"), 5,
                new BigDecimal("20000"), "home repair", 36, new BigDecimal("500"), "Acme", List.of("a.pdf")), kit);
        ctx.addReport(new AgentReport("kyc", AgentReport.Status.PASS, 10, "ok", List.of("o"), List.of()));
    }

    private static LoanDecision approved() {
        return new LoanDecision("A1", LoanDecision.Decision.APPROVED, new BigDecimal("20000"), new BigDecimal("7.1"),
                List.of("autopay"), List.of(), "solid file", List.of(), 0, Instant.now());
    }

    private static CriticAgent.CritiqueReport consistent(boolean ok) {
        return new CriticAgent.CritiqueReport(ok, ok ? List.of() : List.of("pricing mismatch"), "APPROVED", "x");
    }

    private Outcome run(ChallengeCoordinator.OwnerRechecker rechecker) {
        return coordinator.run(new ChallengeCoordinator.Request(ctx, "", () -> envelope, rechecker));
    }

    private void injectConflict() {
        for (String[] a : new String[][]{{"kyc", "Acme"}, {"credit-risk", "Beta"}}) {
            ctx.state().append(new Finding("f-" + a[0], "A1", "loan", a[0], Finding.Kind.CHECK_RESULT,
                    Finding.Status.PASS, Map.of("employer", a[1]), List.of(), "n", List.of(), List.of(), "HIGH",
                    null, Provenance.of("m", false, 1), false, Finding.SCHEMA_VERSION, Instant.now()));
        }
    }

    @Test
    void cleanCandidateResolvesInRoundZero() {
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(critic.critique(any(), any())).thenReturn(consistent(true));

        Outcome o = run(null);

        assertThat(o.termination()).isEqualTo(Termination.RESOLVED);
        assertThat(o.rounds()).isZero();
        assertThat(o.needsHuman(2)).isFalse();
    }

    @Test
    void criticIssueIsFixedByOneRedraft() {
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(decisionAgent.decide(any(), anyString(), anyString(), anyInt())).thenReturn(approved());
        when(critic.critique(any(), any())).thenReturn(consistent(false), consistent(true));

        Outcome o = run(null);

        assertThat(o.termination()).isEqualTo(Termination.RESOLVED);
        assertThat(o.rounds()).isEqualTo(1);
    }

    @Test
    void stuckCriticStopsOnNonConvergenceAndNeedsHuman() {
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(decisionAgent.decide(any(), anyString(), anyString(), anyInt())).thenReturn(approved());
        when(critic.critique(any(), any())).thenReturn(consistent(false));

        Outcome o = run(null);

        assertThat(o.termination()).isEqualTo(Termination.EXHAUSTED);
        assertThat(o.open()).hasSize(1);
        assertThat(o.needsHuman(2)).isTrue();
        assertThat(o.evidence()).anyMatch(l -> l.contains("Open challenge [CRITIC]"));
        verify(decisionAgent, times(1)).decide(any(), anyString(), anyString(), anyInt());
    }

    @Test
    void criticIsSkippedWhileCodeAlreadyFoundABlockingIssue() {
        ctx.addReport(new AgentReport("credit-risk", AgentReport.Status.FAIL, 50, "bad", List.of("o"), List.of()));
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        LoanDecision declined = new LoanDecision("A1", LoanDecision.Decision.DECLINED, BigDecimal.ZERO, null,
                List.of(), List.of("FICO low"), "declined", List.of(), 0, Instant.now());
        when(decisionAgent.decide(any(), anyString(), anyString(), anyInt())).thenReturn(declined);
        when(critic.critique(any(), any())).thenReturn(consistent(true));

        Outcome o = run(null);

        assertThat(o.termination()).isEqualTo(Termination.RESOLVED);
        assertThat(o.decision().decision()).isEqualTo(LoanDecision.Decision.DECLINED);
        verify(critic, times(1)).critique(any(), any());
    }

    @Test
    void everyRevisionIsRevalidatedIncludingTheLast() {
        props.setMaxRounds(1);
        ctx.addReport(new AgentReport("credit-risk", AgentReport.Status.FAIL, 50, "bad", List.of("o"), List.of()));
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(decisionAgent.decide(any(), anyString(), anyString(), anyInt())).thenReturn(approved());

        Outcome o = run(null);

        assertThat(o.termination()).isEqualTo(Termination.EXHAUSTED);
        assertThat(o.open()).anyMatch(i -> i.ruleId().equals(ContradictionDetector.APPROVED_DESPITE_FAIL));
        verify(critic, never()).critique(any(), any());
    }

    @Test
    void ownerRecheckThatCorrectsTheFactClosesTheChallenge() {
        injectConflict();
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(decisionAgent.decide(any(), anyString(), anyString(), anyInt())).thenReturn(approved());
        when(critic.critique(any(), any())).thenReturn(consistent(true));
        AtomicInteger rechecks = new AtomicInteger();

        Outcome o = run((agent, claim) -> {
            rechecks.incrementAndGet();
            ctx.state().append(new Finding("f-fixed", "A1", "loan", agent, Finding.Kind.CHECK_RESULT,
                    Finding.Status.PASS, Map.of("employer", "Acme"), List.of(), "n", List.of(), List.of(), "HIGH",
                    null, Provenance.of("m", false, 1), false, Finding.SCHEMA_VERSION, Instant.now()));
            return Optional.of(new AgentReport(agent, AgentReport.Status.WARN, 35, "corrected", List.of(), List.of()));
        });

        assertThat(rechecks).hasValue(1);
        assertThat(o.termination()).isEqualTo(Termination.RESOLVED);
        assertThat(o.rebutted()).isEmpty();
        assertThat(ctx.state().messages()).extracting(AgentMessage::type)
                .contains(AgentMessage.Type.CHALLENGE, AgentMessage.Type.CHALLENGE_RESPONSE);
    }

    @Test
    void rebuttalIsRecordedAndNeverClosesABlockingIssue() {
        injectConflict();
        ctx.addReport(new AgentReport("credit-risk", AgentReport.Status.PASS, 20, "stands", List.of(), List.of()));
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(decisionAgent.decide(any(), anyString(), anyString(), anyInt())).thenReturn(approved());
        when(critic.critique(any(), any())).thenReturn(consistent(true));

        Outcome o = run((agent, claim) -> Optional.of(
                new AgentReport(agent, AgentReport.Status.PASS, 20, "stands", List.of(), List.of())));

        assertThat(o.rebutted()).hasSize(1);
        assertThat(o.open()).isNotEmpty();
        assertThat(o.termination()).isNotEqualTo(Termination.RESOLVED);
        assertThat(o.needsHuman(2)).isTrue();
    }

    @Test
    void correctedFactWithUnchangedStatusAndRiskIsNotARebuttal() {
        ctx.addReport(new AgentReport("credit-risk", AgentReport.Status.PASS, 20, "original", List.of(), List.of()));
        injectConflict();
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(decisionAgent.decide(any(), anyString(), anyString(), anyInt())).thenReturn(approved());
        when(critic.critique(any(), any())).thenReturn(consistent(true));

        Outcome outcome = run((agent, claim) -> {
            ctx.state().append(new Finding("corrected-evidence", "A1", "loan", agent, Finding.Kind.CHECK_RESULT,
                    Finding.Status.PASS, Map.of("employer", "Acme"), List.of(), "corrected", List.of(), List.of(),
                    "HIGH", null, Provenance.of("m", false, 1), false, Finding.SCHEMA_VERSION, Instant.now()));
            return Optional.of(new AgentReport(agent, AgentReport.Status.PASS, 20, "corrected", List.of(), List.of()));
        });

        assertThat(outcome.termination()).isEqualTo(Termination.RESOLVED);
        assertThat(outcome.rounds()).isEqualTo(1);
        assertThat(outcome.open()).isEmpty();
        assertThat(outcome.rebutted()).isEmpty();
        assertThat(outcome.needsHuman(2)).isFalse();
        verify(critic, times(1)).critique(any(), any());
    }

    @Test
    void deletingTheChallengedFactCannotTurnAnUnverifiedCorrectionIntoAutonomousApproval() {
        ctx.addReport(new AgentReport("credit-risk", AgentReport.Status.PASS, 20, "original", List.of(), List.of()));
        injectConflict();
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(decisionAgent.decide(any(), anyString(), anyString(), anyInt())).thenReturn(approved());
        when(critic.critique(any(), any())).thenReturn(consistent(true));

        Outcome outcome = run((agent, claim) -> {
            ctx.state().append(new Finding("dropped-evidence", "A1", "loan", agent, Finding.Kind.CHECK_RESULT,
                    Finding.Status.PASS, Map.of(), List.of(), "no facts", List.of(), List.of(),
                    "HIGH", null, Provenance.of("m", false, 1), false, Finding.SCHEMA_VERSION, Instant.now()));
            return Optional.of(new AgentReport(agent, AgentReport.Status.PASS, 10, "changed risk", List.of(), List.of()));
        });

        assertThat(outcome.rebutted()).hasSize(1);
        assertThat(outcome.needsHuman(2)).isTrue();
        assertThat(outcome.evidence()).anyMatch(line -> line.contains("CONFLICTING_FACT"));
    }

    @Test
    void challengeCapEscalatesInsteadOfLoopingOnTheSameAgent() {
        injectConflict();
        props.setMaxRounds(5);
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(decisionAgent.decide(any(), anyString(), anyString(), anyInt())).thenReturn(approved());
        AtomicInteger rechecks = new AtomicInteger();

        Outcome o = run((agent, claim) -> {
            rechecks.incrementAndGet();
            return Optional.empty();
        });

        assertThat(rechecks).hasValue(1);
        assertThat(o.termination()).isIn(Termination.ESCALATED, Termination.EXHAUSTED);
        assertThat(o.needsHuman(2)).isTrue();
    }

    @Test
    void criticOutageOnAnApprovalFailsSafe() {
        when(decisionAgent.decide(any(), anyString())).thenReturn(approved());
        when(critic.critique(any(), any())).thenThrow(new IllegalStateException("down"));

        Outcome o = run(null);

        assertThat(o.termination()).isEqualTo(Termination.UNAVAILABLE);
        assertThat(o.reason()).contains("Quality reviewer unavailable");
    }

    @Test
    void decisionAgentOutageIsUnavailableWithNoCandidate() {
        when(decisionAgent.decide(any(), anyString())).thenThrow(new IllegalStateException("down"));

        Outcome o = run(null);

        assertThat(o.termination()).isEqualTo(Termination.UNAVAILABLE);
        assertThat(o.decision()).isNull();
    }

    @Test
    void autonomyLevelOnlyTightensAdvisoryHandling() {
        Outcome advisoryOnly = new Outcome(Termination.RESOLVED, approved(), List.of(), List.of(),
                List.of(new ChallengeIssue("X", ChallengeIssue.Severity.ADVISORY, "decision", "c",
                        ChallengeIssue.Source.DETECTOR)), List.of(), 0, null);
        assertThat(advisoryOnly.needsHuman(2)).isFalse();
        assertThat(advisoryOnly.needsHuman(1)).isTrue();
    }
}
