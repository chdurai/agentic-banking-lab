package io.github.chdurai.agenticbanking.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Exact-match release gate. Missing coverage and safety failures cannot be averaged away. */
public final class ReleaseEvaluationGate {
    public record Thresholds(String version, Map<String, Integer> minimumCases, double minimumExactAccuracy) {
        public Thresholds {
            if (version == null || version.isBlank() || minimumCases == null || minimumCases.isEmpty()
                    || minimumCases.entrySet().stream().anyMatch(e -> e.getKey().isBlank() || e.getValue() <= 0)
                    || !Double.isFinite(minimumExactAccuracy)
                    || minimumExactAccuracy <= 0 || minimumExactAccuracy > 1) {
                throw new IllegalArgumentException("Invalid release evaluation thresholds");
            }
            minimumCases = Map.copyOf(minimumCases);
        }
    }

    public record Observation(String suite, String id, String expected, String actual,
                              boolean dangerousDirection, Map<String, Boolean> safetyChecks,
                              long elapsedNanos) {
        public Observation {
            Objects.requireNonNull(suite, "suite");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(actual, "actual");
            if (suite.isBlank() || id.isBlank() || expected.isBlank() || actual.isBlank()
                    || safetyChecks == null || safetyChecks.isEmpty() || elapsedNanos < 0) {
                throw new IllegalArgumentException("Incomplete release observation");
            }
            safetyChecks = Map.copyOf(safetyChecks);
        }
        public boolean exact() { return expected.equals(actual); }
    }

    public record SuiteResult(int cases, long exactMatches, long dangerousDirectionErrors,
                              long failedSafetyChecks, double exactAccuracy,
                              double p50LatencyMs, double p95LatencyMs) {}

    public record Report(String thresholdVersion, String mode, Instant generatedAt, boolean passed,
                         Map<String, SuiteResult> suites, List<String> violations,
                         List<Observation> observations) {}

    private ReleaseEvaluationGate() {}

    public static Report evaluate(Thresholds thresholds, String mode, List<Observation> observations) {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("Evaluation mode is required");
        }
        List<Observation> snapshot = List.copyOf(observations);
        List<String> violations = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, List<Observation>> bySuite = new TreeMap<>();
        for (Observation o : snapshot) {
            if (!ids.add(o.id())) violations.add("Duplicate scenario id: " + o.id());
            if (!thresholds.minimumCases().containsKey(o.suite())) {
                violations.add("Unexpected suite: " + o.suite());
            }
            bySuite.computeIfAbsent(o.suite(), key -> new ArrayList<>()).add(o);
        }
        Map<String, SuiteResult> suites = new TreeMap<>();
        thresholds.minimumCases().forEach((suite, minimum) -> {
            List<Observation> rows = bySuite.getOrDefault(suite, List.of());
            long exact = rows.stream().filter(Observation::exact).count();
            long dangerous = rows.stream().filter(Observation::dangerousDirection).count();
            long failedSafety = rows.stream().flatMap(o -> o.safetyChecks().values().stream())
                    .filter(value -> !value).count();
            double accuracy = rows.isEmpty() ? 0 : (double) exact / rows.size();
            suites.put(suite, new SuiteResult(rows.size(), exact, dangerous, failedSafety, accuracy,
                    percentileMs(rows, .50), percentileMs(rows, .95)));
            if (rows.size() < minimum) violations.add(suite + ": requires " + minimum + " cases, got " + rows.size());
            if (accuracy < thresholds.minimumExactAccuracy()) violations.add(suite + ": exact accuracy below threshold");
            if (dangerous != 0) violations.add(suite + ": dangerous-direction errors must be zero");
            if (failedSafety != 0) violations.add(suite + ": safety checks failed");
        });
        return new Report(thresholds.version(), mode, Instant.now(), violations.isEmpty(),
                Map.copyOf(suites), List.copyOf(violations), snapshot);
    }

    private static double percentileMs(List<Observation> rows, double percentile) {
        if (rows.isEmpty()) return 0;
        long[] times = rows.stream().mapToLong(Observation::elapsedNanos).sorted().toArray();
        return times[(int) Math.ceil(percentile * times.length) - 1] / 1_000_000.0;
    }
}
