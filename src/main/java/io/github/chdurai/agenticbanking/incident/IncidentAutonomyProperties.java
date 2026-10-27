package io.github.chdurai.agenticbanking.incident;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;

/**
 * Class-based incident autonomy levels.
 *
 * <p>Keys are class selectors under {@code platform.autonomy.incident.<class>.level}, for example:
 * {@code sev3.level=L2}, {@code security.level=L1}, {@code database.sev3.level=L2}.</p>
 */
@ConfigurationProperties("platform.autonomy.incident")
public class IncidentAutonomyProperties extends LinkedHashMap<String, IncidentAutonomyProperties.ClassPolicy> {

    public static final String DEFAULT_CLASS = "default";

    public AutonomyLevel levelFor(String classKey) {
        ClassPolicy policy = get(classKey);
        if (policy != null && policy.getLevel() != null) {
            return policy.getLevel();
        }
        ClassPolicy fallback = get(DEFAULT_CLASS);
        if (fallback != null && fallback.getLevel() != null) {
            return fallback.getLevel();
        }
        return AutonomyLevel.L1;
    }

    public static class ClassPolicy {
        private AutonomyLevel level = AutonomyLevel.L1;

        public AutonomyLevel getLevel() {
            return level;
        }

        public void setLevel(AutonomyLevel level) {
            this.level = level;
        }
    }
}
