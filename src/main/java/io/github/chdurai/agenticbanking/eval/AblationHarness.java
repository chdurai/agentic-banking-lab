package io.github.chdurai.agenticbanking.eval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Paired experiments only: no production routing changes or inference from missing model runs. */
public final class AblationHarness {
    public enum Mode { DETERMINISTIC_STUB, LIVE }
    public enum Verdict { UNMEASURED, BASELINE, INVALID_BASELINE, SAFETY_REGRESSION, OUTCOME_REGRESSION, NO_OBSERVED_REGRESSION }

    public record Scenario(String id, String suite, String expected) {
        public Scenario { text(id); text(suite); text(expected); }
    }

    public record Variant(String id, String hypothesis, String modelFamily, String configuration, String limitation) {
        public Variant { text(id); text(hypothesis); text(modelFamily); text(configuration); text(limitation); }
    }

    public record Plan(String version, String corpusVersion, Mode mode, String baseline,
                       Set<String> requiredFamilies, List<Scenario> scenarios, List<Variant> variants) {
        public Plan {
            text(version); text(corpusVersion); text(baseline); Objects.requireNonNull(mode);
            requiredFamilies = Set.copyOf(requiredFamilies);
            scenarios = List.copyOf(scenarios);
            variants = List.copyOf(variants);
            if (requiredFamilies.isEmpty() || scenarios.isEmpty() || variants.isEmpty()) {
                throw new IllegalArgumentException("Ablations require model families, scenarios and variants");
            }
            requiredFamilies.forEach(AblationHarness::text);
            unique(scenarios.stream().map(Scenario::id).toList(), "scenario");
            unique(variants.stream().map(Variant::id).toList(), "variant");
            if (variants.stream().noneMatch(v -> v.id().equals(baseline))) {
                throw new IllegalArgumentException("Baseline variant missing");
            }
            Set<String> families = new HashSet<>();
            variants.forEach(v -> families.add(v.modelFamily()));
            if (!families.containsAll(requiredFamilies)) {
                throw new IllegalArgumentException("Required model-family matrix columns missing");
            }
        }
    }

    /** Nullable usage/cost means unknown, never a zero-price or zero-token fallback. */
    public record Usage(long calls, Long promptTokens, Long completionTokens, Double estimatedCostUsd) {
        public Usage {
            if (calls < 0 || promptTokens != null && promptTokens < 0
                    || completionTokens != null && completionTokens < 0
                    || estimatedCostUsd != null && (!Double.isFinite(estimatedCostUsd) || estimatedCostUsd < 0)) {
                throw new IllegalArgumentException("Invalid ablation usage");
            }
        }
    }

    public record Result(String actual, boolean dangerousDirection, Map<String, Boolean> safetyChecks,
                         Map<String, Usage> byAgent, Double explanationScore) {
        public Result {
            text(actual);
            safetyChecks = Map.copyOf(safetyChecks);
            byAgent = Map.copyOf(byAgent);
            if (safetyChecks.isEmpty() || explanationScore != null
                    && (!Double.isFinite(explanationScore) || explanationScore < 0 || explanationScore > 1)) {
                throw new IllegalArgumentException("Incomplete ablation result");
            }
            safetyChecks.keySet().forEach(AblationHarness::text);
            byAgent.keySet().forEach(AblationHarness::text);
        }
    }

    public record Observation(String scenarioId, String suite, String expected, Result result,
                              long elapsedNanos, String errorType) {
        public boolean exact() { return errorType == null && expected.equals(result.actual()); }
        public boolean safe() {
            return errorType == null && !result.dangerousDirection()
                    && result.safetyChecks().values().stream().allMatch(Boolean::booleanValue);
        }
    }

    public record Summary(Variant variant, Verdict verdict, int cases, long exactMatches,
                          long unsafeCases, long errors, Double exactAccuracy, Double accuracyDelta,
                          Double p50LatencyMs, Double p95LatencyMs, Map<String, Usage> byAgent,
                          Double explanationMean, List<Observation> observations) {}

    public record Report(String version, String corpusVersion, Mode mode, Instant generatedAt,
                         String baseline, Set<String> requiredFamilies, boolean allVariantsMeasured,
                         boolean baselineValid, Map<String, Summary> variants, List<String> limitations) {}

    @FunctionalInterface public interface Runner { Result run(Scenario scenario); }

    private AblationHarness() {}

    public static Report run(Plan plan, Map<String, Runner> runners) {
        Objects.requireNonNull(plan);
        runners = Map.copyOf(runners);
        Set<String> declared = new HashSet<>();
        plan.variants().forEach(v -> declared.add(v.id()));
        if (!declared.containsAll(runners.keySet())) throw new IllegalArgumentException("Undeclared runner");
        Map<String, List<Observation>> observations = new LinkedHashMap<>();
        // Interleave paired runs, rather than running all baseline cases before any candidate.
        for (Scenario scenario : plan.scenarios()) {
            for (Variant variant : plan.variants()) {
                Runner runner = runners.get(variant.id());
                if (runner == null) continue;
                long start = System.nanoTime();
                Result result;
                String error = null;
                try {
                    result = Objects.requireNonNull(runner.run(scenario), "Missing ablation result");
                } catch (RuntimeException failure) {
                    error = failure.getClass().getName();
                    result = new Result("ERROR", true, Map.of("execution", false), Map.of(), null);
                }
                observations.computeIfAbsent(variant.id(), id -> new ArrayList<>()).add(new Observation(
                        scenario.id(), scenario.suite(), scenario.expected(), result, System.nanoTime() - start, error));
            }
        }
        List<Observation> baseline = observations.getOrDefault(plan.baseline(), List.of());
        boolean baselineValid = baseline.size() == plan.scenarios().size()
                && baseline.stream().allMatch(o -> o.exact() && o.safe());
        Map<String, Summary> summaries = new LinkedHashMap<>();
        for (Variant variant : plan.variants()) {
            List<Observation> rows = observations.getOrDefault(variant.id(), List.of());
            long exact = rows.stream().filter(Observation::exact).count();
            long unsafe = rows.stream().filter(o -> !o.safe()).count();
            long errors = rows.stream().filter(o -> o.errorType() != null).count();
            double accuracy = rows.isEmpty() ? 0 : (double) exact / rows.size();
            double baselineAccuracy = baseline.isEmpty() ? 0
                    : baseline.stream().filter(Observation::exact).count() / (double) baseline.size();
            boolean checksComparable = comparableChecks(baseline, rows);
            Verdict verdict = rows.isEmpty() ? Verdict.UNMEASURED
                    : !baselineValid ? Verdict.INVALID_BASELINE
                    : variant.id().equals(plan.baseline()) ? Verdict.BASELINE
                    : unsafe > 0 || !checksComparable ? Verdict.SAFETY_REGRESSION
                    : exact < rows.size() ? Verdict.OUTCOME_REGRESSION : Verdict.NO_OBSERVED_REGRESSION;
            summaries.put(variant.id(), new Summary(variant, verdict, rows.size(), exact, unsafe, errors,
                    rows.isEmpty() ? null : accuracy, rows.isEmpty() || baseline.isEmpty() ? null : accuracy - baselineAccuracy,
                    percentile(rows, .5), percentile(rows, .95), usage(rows), explanation(rows), List.copyOf(rows)));
        }
        return new Report(plan.version(), plan.corpusVersion(), plan.mode(), Instant.now(), plan.baseline(),
                plan.requiredFamilies(), observations.size() == plan.variants().size(), baselineValid, Map.copyOf(summaries),
                List.of("NO_OBSERVED_REGRESSION is descriptive, not statistical non-inferiority or permission to remove an agent.",
                        "Missing runner, usage, pricing or judge scores remain unmeasured; no values are fabricated.",
                        plan.mode() == Mode.DETERMINISTIC_STUB
                                ? "Synthetic stub results do not validate live-model accuracy, explanation quality, latency or billing."
                                : "Live results require independent review and calibrated judges before deployment decisions."));
    }

    private static boolean comparableChecks(List<Observation> baseline, List<Observation> rows) {
        if (baseline.size() != rows.size()) return false;
        for (int i = 0; i < rows.size(); i++) {
            if (!baseline.get(i).scenarioId().equals(rows.get(i).scenarioId())
                    || !baseline.get(i).result().safetyChecks().keySet().equals(rows.get(i).result().safetyChecks().keySet())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Usage> usage(List<Observation> rows) {
        Map<String, List<Usage>> agents = new LinkedHashMap<>();
        rows.forEach(row -> row.result().byAgent().forEach((agent, usage) ->
                agents.computeIfAbsent(agent, id -> new ArrayList<>()).add(usage)));
        Map<String, Usage> totals = new LinkedHashMap<>();
        agents.forEach((agent, usages) -> {
            long calls = 0;
            Long prompt = 0L;
            Long completion = 0L;
            Double cost = 0.0;
            for (Usage usage : usages) {
                calls = Math.addExact(calls, usage.calls());
                prompt = prompt == null || usage.promptTokens() == null ? null : Math.addExact(prompt, usage.promptTokens());
                completion = completion == null || usage.completionTokens() == null ? null
                        : Math.addExact(completion, usage.completionTokens());
                cost = cost == null || usage.estimatedCostUsd() == null ? null : cost + usage.estimatedCostUsd();
                if (cost != null && !Double.isFinite(cost)) throw new IllegalArgumentException("Ablation cost overflow");
            }
            totals.put(agent, new Usage(calls, prompt, completion, cost));
        });
        return Map.copyOf(totals);
    }

    private static Double explanation(List<Observation> rows) {
        return rows.isEmpty() || rows.stream().anyMatch(o -> o.result().explanationScore() == null) ? null
                : rows.stream().mapToDouble(o -> o.result().explanationScore()).average().orElseThrow();
    }

    private static Double percentile(List<Observation> rows, double p) {
        if (rows.isEmpty()) return null;
        long[] times = rows.stream().mapToLong(Observation::elapsedNanos).sorted().toArray();
        return times[(int) Math.ceil(p * times.length) - 1] / 1_000_000.0;
    }

    private static void unique(List<String> ids, String kind) {
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("Duplicate " + kind);
    }

    private static void text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Ablation metadata must not be blank");
    }
}
