package io.github.chdurai.agenticbanking.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseEvaluationGateTest {
    private final ReleaseEvaluationGate.Thresholds thresholds =
            new ReleaseEvaluationGate.Thresholds("v1", Map.of("loan", 1, "document", 1), .5);

    private ReleaseEvaluationGate.Observation row(String suite, String id, String expected, String actual,
                                                  boolean dangerous, boolean safe) {
        return new ReleaseEvaluationGate.Observation(suite, id, expected, actual, dangerous,
                Map.of("evidence", safe), 1_000_000);
    }

    @Test
    void missingOrEmptySuitesFailEvenWithPerfectAvailableResults() {
        assertThat(ReleaseEvaluationGate.evaluate(thresholds, "stub", List.of()).passed()).isFalse();
        var report = ReleaseEvaluationGate.evaluate(thresholds, "stub",
                List.of(row("loan", "L1", "APPROVED", "APPROVED", false, true)));
        assertThat(report.violations()).anyMatch(v -> v.startsWith("document: requires"));
        assertThat(report.passed()).isFalse();
    }

    @Test
    void oneDangerousErrorCannotBeHiddenByAccuracyOrConservativeCredit() {
        var report = ReleaseEvaluationGate.evaluate(thresholds, "stub", List.of(
                row("loan", "L1", "MANUAL_REVIEW", "APPROVED", true, true),
                row("loan", "L2", "APPROVED", "APPROVED", false, true),
                row("document", "D1", "ANALYZED", "ANALYZED", false, true)));
        assertThat(report.suites().get("loan").exactAccuracy()).isEqualTo(.5);
        assertThat(report.violations()).contains("loan: dangerous-direction errors must be zero");
        assertThat(report.passed()).isFalse();
    }

    @Test
    void safetyFailureAndDuplicateIdsCannotProducePassingReport() {
        var report = ReleaseEvaluationGate.evaluate(thresholds, "stub", List.of(
                row("loan", "same-id", "APPROVED", "APPROVED", false, false),
                row("document", "same-id", "ANALYZED", "ANALYZED", false, true)));
        assertThat(report.violations()).contains("Duplicate scenario id: same-id", "loan: safety checks failed");
    }

    @Test
    void exactResultsHaveMeasuredPercentilesAndPass() {
        var report = ReleaseEvaluationGate.evaluate(thresholds, "deterministic-stub", List.of(
                row("loan", "L1", "MANUAL_REVIEW", "MANUAL_REVIEW", false, true),
                row("document", "D1", "ANALYZED", "ANALYZED", false, true)));
        assertThat(report.passed()).isTrue();
        assertThat(report.suites().get("loan").p95LatencyMs()).isEqualTo(1);
    }

    @Test
    void invalidThresholdsAndMissingChecksAreRejected() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, -1, 1.01}) {
            assertThatThrownBy(() -> new ReleaseEvaluationGate.Thresholds("v1", Map.of("loan", 1), value))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ReleaseEvaluationGate.Observation("loan", "L", "A", "A", false, Map.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
