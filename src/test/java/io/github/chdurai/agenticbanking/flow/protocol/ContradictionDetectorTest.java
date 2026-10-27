package io.github.chdurai.agenticbanking.flow.protocol;

import io.github.chdurai.agenticbanking.flow.state.Finding;
import io.github.chdurai.agenticbanking.flow.state.Provenance;
import io.github.chdurai.agenticbanking.model.AgentReport;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContradictionDetectorTest {

    private final ContradictionDetector detector = new ContradictionDetector();

    private static AgentReport report(String agent, AgentReport.Status st, int score) {
        return new AgentReport(agent, st, score, agent + " summary", List.of("o"), List.of());
    }

    private static LoanDecision draft(LoanDecision.Decision d, String reasoning) {
        return new LoanDecision("A1", d, BigDecimal.ZERO, null, List.of(), List.of(), reasoning, List.of(), 0,
                Instant.now());
    }

    private static Finding fact(String agent, String key, Object value) {
        return new Finding("f-" + agent + key, "A1", "loan", agent, Finding.Kind.CHECK_RESULT, Finding.Status.PASS,
                Map.of(key, value), List.of(), "n", List.of(), List.of(), "HIGH", null,
                Provenance.of("m", false, 1), false, Finding.SCHEMA_VERSION, Instant.now());
    }

    @Test
    void approvalDespiteFailIsBlockingOnTheDecision() {
        List<ChallengeIssue> issues = detector.detect(draft(LoanDecision.Decision.APPROVED, "fine"),
                List.of(report("kyc", AgentReport.Status.FAIL, 80)), List.of());
        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.ruleId()).isEqualTo(ContradictionDetector.APPROVED_DESPITE_FAIL);
            assertThat(i.blocking()).isTrue();
            assertThat(i.aimedAtDecision()).isTrue();
        });
    }

    @Test
    void declineWithFailIsNotAContradiction() {
        assertThat(detector.detect(draft(LoanDecision.Decision.DECLINED, "fail"),
                List.of(report("kyc", AgentReport.Status.FAIL, 80)), List.of())).isEmpty();
    }

    @Test
    void citedScoreMustMatchAFindingOrTheAverage() {
        List<AgentReport> reports = List.of(report("kyc", AgentReport.Status.PASS, 10),
                report("credit-risk", AgentReport.Status.PASS, 30));
        assertThat(detector.detect(draft(LoanDecision.Decision.APPROVED, "overall risk score 20 is low"), reports,
                List.of())).isEmpty();
        assertThat(detector.detect(draft(LoanDecision.Decision.APPROVED, "Risk score of 77 is low"), reports,
                List.of())).singleElement().satisfies(i -> {
            assertThat(i.ruleId()).isEqualTo(ContradictionDetector.UNGROUNDED_RISK_SCORE);
            assertThat(i.blocking()).isFalse();
        });
    }

    @Test
    void conflictingFactChallengesTheLaterAsserter() {
        List<ChallengeIssue> issues = detector.conflictingFacts(List.of(
                fact("kyc", "employer", "Acme"), fact("credit-risk", "employer", "Beta")));
        assertThat(issues).singleElement().satisfies(i -> {
            assertThat(i.ruleId()).isEqualTo(ContradictionDetector.CONFLICTING_FACT);
            assertThat(i.targetAgent()).isEqualTo("credit-risk");
            assertThat(i.blocking()).isTrue();
        });
    }

    @Test
    void agreeingFactsAndPerAgentRiskScoresDoNotConflict() {
        assertThat(detector.conflictingFacts(List.of(
                fact("kyc", "employer", "Acme"), fact("credit-risk", "employer", "Acme"),
                fact("kyc", "riskScore", 10), fact("credit-risk", "riskScore", 90)))).isEmpty();
    }

    @Test
    void sameInputGivesSameIssues() {
        LoanDecision d = draft(LoanDecision.Decision.APPROVED, "risk score 99");
        List<AgentReport> r = List.of(report("kyc", AgentReport.Status.FAIL, 80));
        assertThat(detector.detect(d, r, List.of())).isEqualTo(detector.detect(d, r, List.of()));
    }
}
