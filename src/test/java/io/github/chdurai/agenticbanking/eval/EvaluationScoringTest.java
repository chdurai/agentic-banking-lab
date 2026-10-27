package io.github.chdurai.agenticbanking.eval;

import io.github.chdurai.agenticbanking.model.LoanDecision;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationScoringTest {

    private EvaluationResult.CaseOutcome outcome(LoanDecision.Decision expected,
                                                 LoanDecision.Decision actual) {
        boolean exact = expected == actual;
        boolean acceptable = exact || switch (expected) {
            case MANUAL_REVIEW -> actual == LoanDecision.Decision.DECLINED;
            case APPROVED -> actual == LoanDecision.Decision.MANUAL_REVIEW;
            case DECLINED -> actual == LoanDecision.Decision.COMPLIANCE_HOLD;
            case COMPLIANCE_HOLD -> false;
        };
        return new EvaluationResult.CaseOutcome("T", "test", expected, actual,
                exact, acceptable, 0);
    }

    @Test
    void exactMatchesScoreFull() {
        var result = EvaluationRunner.aggregate(List.of(
                outcome(LoanDecision.Decision.APPROVED, LoanDecision.Decision.APPROVED),
                outcome(LoanDecision.Decision.DECLINED, LoanDecision.Decision.DECLINED)));
        assertThat(result.exactMatches()).isEqualTo(2);
        assertThat(result.accuracy()).isEqualTo(1.0);
    }

    @Test
    void conservativeAdjacentOutcomeIsAcceptableButNotExact() {
        var result = EvaluationRunner.aggregate(List.of(
                outcome(LoanDecision.Decision.MANUAL_REVIEW, LoanDecision.Decision.DECLINED)));
        assertThat(result.exactMatches()).isZero();
        assertThat(result.acceptableMatches()).isEqualTo(1);
        assertThat(result.accuracy()).isEqualTo(1.0);
    }

    @Test
    void dangerousOutcomeScoresZero() {
        // Expected DECLINED but pipeline APPROVED - the worst failure mode in lending
        var result = EvaluationRunner.aggregate(List.of(
                outcome(LoanDecision.Decision.DECLINED, LoanDecision.Decision.APPROVED)));
        assertThat(result.acceptableMatches()).isZero();
        assertThat(result.accuracy()).isZero();
    }

    @Test
    void emptySuiteIsZero() {
        var result = EvaluationRunner.aggregate(List.of());
        assertThat(result.total()).isZero();
        assertThat(result.accuracy()).isZero();
    }
}
