package io.github.chdurai.agenticbanking.incident;

import java.util.List;

/**
 * Ordered remediation steps. Steps with requiresApproval=false and LOW risk are
 * eligible for autonomous execution on SEV3/SEV4 incidents.
 */
public record RemediationPlan(
        List<Step> steps,
        int estimatedMinutes,
        String rollbackPlan
) {
    public record Step(
            String action,
            java.util.Map<String, String> params,
            String command,
            RiskLevel riskLevel,
            boolean requiresApproval
    ) {
        public Step {
            params = params == null ? java.util.Map.of() : java.util.Map.copyOf(params);
        }

        /** Backward-compatible constructor for tests and old stubs that send command text. */
        public Step(String action, String command, RiskLevel riskLevel, boolean requiresApproval) {
            this(action, java.util.Map.of(), command, riskLevel, requiresApproval);
        }
    }

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    public boolean isFullyAutonomous() {
        return steps != null && !steps.isEmpty() && steps.stream()
                .allMatch(s -> s.riskLevel() == RiskLevel.LOW && !s.requiresApproval());
    }
}
