package io.github.chdurai.agenticbanking.eval;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static io.github.chdurai.agenticbanking.eval.AblationHarness.*;

class AblationHarnessTest {
    private final Variant baseline = new Variant("base", "control", "stub", "current", "Synthetic only");
    private final Variant candidate = new Variant("candidate", "H3", "stub", "no-critic", "Injected cases only");
    private final Variant kimi = new Variant("kimi", "H4", "kimi-k3", "small-triage", "Live credentials/review required");
    private final List<Scenario> cases = List.of(new Scenario("one", "loan", "APPROVED"), new Scenario("two", "loan", "APPROVED"));

    private Plan plan() {
        return new Plan("v1", "corpus-v1", Mode.DETERMINISTIC_STUB, "base", Set.of("stub", "kimi-k3"),
                cases, List.of(baseline, candidate, kimi));
    }

    private Result good() {
        return new Result("APPROVED", false, Map.of("policy", true),
                Map.of("critic", new Usage(1, 100L, 50L, null)), null);
    }

    @Test void missingFamilyRunsRemainUnmeasuredAndAreNotZeroCostEvidence() {
        Report report = run(plan(), Map.of("base", scenario -> good(), "candidate", scenario -> good()));
        assertThat(report.baselineValid()).isTrue();
        assertThat(report.allVariantsMeasured()).isFalse();
        assertThat(report.variants().get("candidate").verdict()).isEqualTo(Verdict.NO_OBSERVED_REGRESSION);
        Summary pending = report.variants().get("kimi");
        assertThat(pending.verdict()).isEqualTo(Verdict.UNMEASURED);
        assertThat(pending.exactAccuracy()).isNull();
        assertThat(pending.p95LatencyMs()).isNull();
        assertThat(report.variants().get("base").byAgent().get("critic").estimatedCostUsd()).isNull();
        assertThat(report.variants().get("base").explanationMean()).isNull();
    }

    @Test void dangerousOrMissingChecksCannotBeHiddenByPerfectAccuracy() {
        for (Result unsafe : List.of(new Result("APPROVED", true, Map.of("policy", true), Map.of(), null),
                new Result("APPROVED", false, Map.of("different-check", true), Map.of(), null),
                new Result("APPROVED", false, Map.of("policy", false), Map.of(), null))) {
            Report report = run(plan(), Map.of("base", scenario -> good(), "candidate", scenario -> unsafe));
            assertThat(report.variants().get("candidate").exactAccuracy()).isEqualTo(1);
            assertThat(report.variants().get("candidate").verdict()).isEqualTo(Verdict.SAFETY_REGRESSION);
        }
    }

    @Test void missingOrFailedBaselineCannotJustifyCandidateRemoval() {
        Report missing = run(plan(), Map.of("candidate", scenario -> good()));
        assertThat(missing.variants().get("candidate").verdict()).isEqualTo(Verdict.INVALID_BASELINE);
        Report failing = run(plan(), Map.of("base", scenario -> {
            throw new IllegalStateException("private payload must not enter report");
        }, "candidate", scenario -> good()));
        assertThat(failing.variants().get("base").errors()).isEqualTo(2);
        assertThat(failing.variants().get("base").observations()).allSatisfy(o -> {
            assertThat(o.errorType()).isEqualTo(IllegalStateException.class.getName());
            assertThat(o.safe()).isFalse();
        });
        assertThat(failing.toString()).doesNotContain("private payload");
        assertThat(failing.variants().get("candidate").verdict()).isEqualTo(Verdict.INVALID_BASELINE);
    }

    @Test void outcomeRegressionsAndPartialTelemetryAreExplicit() {
        Report report = run(plan(), Map.of("base", scenario -> good(), "candidate", scenario ->
                new Result(scenario.id().equals("one") ? "APPROVED" : "MANUAL_REVIEW", false,
                        Map.of("policy", true), Map.of("decision",
                        new Usage(1, scenario.id().equals("one") ? 50L : null, 25L, .001)), null)));
        Summary result = report.variants().get("candidate");
        assertThat(result.verdict()).isEqualTo(Verdict.OUTCOME_REGRESSION);
        assertThat(result.accuracyDelta()).isEqualTo(-.5);
        assertThat(result.byAgent().get("decision").calls()).isEqualTo(2);
        assertThat(result.byAgent().get("decision").promptTokens()).isNull();
        assertThat(result.byAgent().get("decision").completionTokens()).isEqualTo(50);
        assertThat(result.byAgent().get("decision").estimatedCostUsd()).isEqualTo(.002);
        assertThat(result.p95LatencyMs()).isNotNegative();
    }

    @Test void experimentsArePairedAndRunnersCannotOmitOrAddCases() {
        var order = new java.util.ArrayList<String>();
        Report report = run(plan(), Map.of("base", scenario -> {
            order.add("base/" + scenario.id()); return good();
        }, "candidate", scenario -> { order.add("candidate/" + scenario.id()); return good(); }));
        assertThat(order).containsExactly("base/one", "candidate/one", "base/two", "candidate/two");
        assertThat(report.variants().get("base").byAgent().get("critic").promptTokens()).isEqualTo(200);
        assertThatThrownBy(() -> run(plan(), Map.of("unknown", scenario -> good()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void malformedPlansAndNonfiniteMetricsFailClosed() {
        assertThatThrownBy(() -> new Plan("v1", "c1", Mode.LIVE, "base", Set.of("kimi-k3"),
                cases, List.of(baseline))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Plan("v1", "c1", Mode.LIVE, "base", Set.of("stub"),
                List.of(cases.getFirst(), cases.getFirst()), List.of(baseline))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Result("APPROVED", false, Map.of(), Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Usage(1, 2L, 3L, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Result("APPROVED", false, Map.of("ok", true), Map.of(), Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
