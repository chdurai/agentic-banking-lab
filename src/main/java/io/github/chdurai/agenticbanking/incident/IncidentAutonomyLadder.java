package io.github.chdurai.agenticbanking.incident;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Resolves the configured incident autonomy class and applies automatic demotion when
 * recent outcomes regress below the configured auto-remediation floor.
 */
@Component
public class IncidentAutonomyLadder {

    public record Decision(String classKey, AutonomyLevel configuredLevel, AutonomyLevel effectiveLevel,
                           boolean demoted, int sampleSize, double autoRate) {
        public boolean allowsAuto() {
            return effectiveLevel.allowsAutomaticAction();
        }
    }

    private final IncidentAutonomyProperties classes;
    private final IncidentPolicyProperties policy;
    private final IncidentMemoryStore memory;

    public IncidentAutonomyLadder(IncidentAutonomyProperties classes, IncidentPolicyProperties policy,
                                  IncidentMemoryStore memory) {
        this.classes = classes;
        this.policy = policy;
        this.memory = memory;
    }

    Decision decide(IncidentTriage triage) {
        String classKey = resolveClassKey(triage);
        AutonomyLevel configured = classes.levelFor(classKey);
        if (!configured.allowsAutomaticAction()) {
            return new Decision(classKey, configured, configured, false, 0, 0.0);
        }
        RegressionSample sample = regressionFor(classKey);
        if (sample.total() >= policy.autonomyRegressionMinSamples()
                && sample.autoRate() < policy.autonomyRegressionMinAutoRate()) {
            return new Decision(classKey, configured, AutonomyLevel.L1,
                    true, sample.total(), sample.autoRate());
        }
        return new Decision(classKey, configured, configured, false, sample.total(), sample.autoRate());
    }

    private RegressionSample regressionFor(String classKey) {
        List<IncidentResolution> recent = memory.recent(policy.autonomyRegressionWindow()).stream()
                .filter(r -> r.triage() != null && matchesClass(classKey, r.triage()))
                .toList();
        if (recent.isEmpty()) {
            return new RegressionSample(0, 0.0);
        }
        long auto = recent.stream().filter(r -> r.status() == IncidentResolution.Status.AUTO_REMEDIATED).count();
        return new RegressionSample(recent.size(), (double) auto / recent.size());
    }

    private static boolean matchesClass(String selectedClass, IncidentTriage triage) {
        if (IncidentAutonomyProperties.DEFAULT_CLASS.equals(selectedClass)) {
            return true;
        }
        return classCandidates(triage).contains(selectedClass);
    }

    private String resolveClassKey(IncidentTriage triage) {
        for (String key : classCandidates(triage)) {
            if (classes.containsKey(key)) {
                return key;
            }
        }
        return IncidentAutonomyProperties.DEFAULT_CLASS;
    }

    private static List<String> classCandidates(IncidentTriage triage) {
        if (triage == null || triage.category() == null || triage.severity() == null) {
            return List.of(IncidentAutonomyProperties.DEFAULT_CLASS);
        }
        String category = triage.category().name().toLowerCase(Locale.ROOT);
        String severity = triage.severity().name().toLowerCase(Locale.ROOT);
        return List.of(category + "." + severity, category, severity, IncidentAutonomyProperties.DEFAULT_CLASS);
    }

    private record RegressionSample(int total, double autoRate) {}
}
