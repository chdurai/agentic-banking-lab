package io.github.chdurai.agenticbanking.ops;

import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
import io.github.chdurai.agenticbanking.memory.CaseMemoryStore;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Model monitoring for the lending decision agent: decision mix, human-review rate, critic
 * revision rate, and drift of the recent window against the earlier baseline. Also reports
 * how often humans approve vs reject queued items, per flow (a proxy for agent trustworthiness).
 * Drift here is a trigger to investigate (prompt, model version or population change), not a verdict.
 */
@Component
public class ModelMonitor {

    static final int MIN_SAMPLE = 20;
    static final double DRIFT_THRESHOLD = 0.15;

    private final CaseMemoryStore memory;
    private final ApprovalQueue approvals;

    public ModelMonitor(CaseMemoryStore memory, ApprovalQueue approvals) {
        this.memory = memory;
        this.approvals = approvals;
    }

    public Map<String, Object> report(int window) {
        List<LoanDecision> all = memory.allDecisions();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("totalDecisions", all.size());
        out.put("decisionMix", mix(all));
        out.put("humanReviewRate", rate(all, LoanDecision.Decision.MANUAL_REVIEW));
        out.put("avgCriticRevisions", all.isEmpty() ? 0.0
                : round(all.stream().mapToInt(LoanDecision::revisionCount).average().orElse(0)));
        out.put("drift", drift(all, window));
        out.put("humanOversight", oversight());
        return out;
    }

    private Map<String, Object> drift(List<LoanDecision> all, int window) {
        int w = Math.max(MIN_SAMPLE, window);
        if (all.size() < 2 * MIN_SAMPLE || all.size() - w < MIN_SAMPLE) {
            return Map.of("status", "INSUFFICIENT_DATA",
                    "detail", "needs at least %d baseline and %d recent decisions".formatted(MIN_SAMPLE, MIN_SAMPLE));
        }
        List<LoanDecision> baseline = all.subList(0, all.size() - w);
        List<LoanDecision> recent = all.subList(all.size() - w, all.size());
        Map<String, Double> base = shares(baseline);
        Map<String, Double> now = shares(recent);
        List<String> alerts = new ArrayList<>();
        Map<String, Double> deltas = new java.util.LinkedHashMap<>();
        for (LoanDecision.Decision d : LoanDecision.Decision.values()) {
            double delta = now.getOrDefault(d.name(), 0.0) - base.getOrDefault(d.name(), 0.0);
            deltas.put(d.name(), round(delta));
            if (Math.abs(delta) > DRIFT_THRESHOLD) {
                alerts.add("%s share shifted %+.0f pts".formatted(d, delta * 100));
            }
        }
        return Map.of("status", alerts.isEmpty() ? "STABLE" : "DRIFT_SUSPECTED", "windowSize", w,
                "baselineSize", baseline.size(), "shareDelta", deltas, "alerts", alerts);
    }

    private Map<String, Object> oversight() {
        Map<String, Map<String, Long>> byFlow = approvals.list(null).stream()
                .collect(Collectors.groupingBy(ApprovalRequest::flow,
                        Collectors.groupingBy(r -> r.status().name(), Collectors.counting())));
        return Map.copyOf(byFlow);
    }

    private static Map<String, Object> mix(List<LoanDecision> decisions) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        var counts = counts(decisions);
        counts.forEach((k, v) -> m.put(k.name(), v));
        return m;
    }

    private static Map<String, Double> shares(List<LoanDecision> decisions) {
        Map<String, Double> m = new java.util.LinkedHashMap<>();
        counts(decisions).forEach((k, v) -> m.put(k.name(), (double) v / decisions.size()));
        return m;
    }

    private static EnumMap<LoanDecision.Decision, Long> counts(List<LoanDecision> decisions) {
        EnumMap<LoanDecision.Decision, Long> counts = new EnumMap<>(LoanDecision.Decision.class);
        for (LoanDecision.Decision d : LoanDecision.Decision.values()) {
            counts.put(d, 0L);
        }
        decisions.forEach(d -> counts.merge(d.decision(), 1L, Long::sum));
        return counts;
    }

    private static double rate(List<LoanDecision> decisions, LoanDecision.Decision target) {
        return decisions.isEmpty() ? 0.0
                : round((double) decisions.stream().filter(d -> d.decision() == target).count() / decisions.size());
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
