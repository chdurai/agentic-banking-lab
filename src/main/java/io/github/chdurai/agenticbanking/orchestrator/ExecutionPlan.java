package io.github.chdurai.agenticbanking.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An execution plan: ordered stages; agents within a stage run in parallel.
 */
public record ExecutionPlan(List<List<String>> stages, String rationale, boolean plannerGenerated) {

    /** Agents no plan may omit: intake (gate), kyc (regulatory), compliance (final check). */
    private static final List<String> REQUIRED = List.of("intake", "kyc", "compliance");

    private static int stageOf(List<List<String>> stages, String agent) {
        for (int i = 0; i < stages.size(); i++) {
            if (stages.get(i).contains(agent)) {
                return i;
            }
        }
        return -1;
    }

    /** Safe default: intake alone, then KYC ∥ credit-risk, then compliance. */
    public static ExecutionPlan defaultPlan() {
        return new ExecutionPlan(
                List.of(List.of("intake"), List.of("kyc", "credit-risk"), List.of("compliance")),
                "rule-based plan", false);
    }

    /**
     * Validates a planner-produced plan against the known agent registry.
     * Rules: non-empty, every name known, no duplicates, intake/kyc/compliance present,
     * intake alone in the first stage, compliance alone in the last (it reads every prior finding).
     */
    public static ExecutionPlan validate(List<List<String>> stages, String rationale,
                                         Set<String> knownAgents) {
        if (stages == null || stages.isEmpty()) {
            throw new IllegalArgumentException("empty plan");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<List<String>> cleaned = new ArrayList<>();
        for (List<String> stage : stages) {
            if (stage == null || stage.isEmpty()) {
                continue;
            }
            List<String> cleanStage = new ArrayList<>();
            for (String name : stage) {
                String n = name == null ? "" : name.trim().toLowerCase();
                if (!knownAgents.contains(n)) {
                    throw new IllegalArgumentException("unknown agent in plan: " + n);
                }
                if (!seen.add(n)) {
                    throw new IllegalArgumentException("duplicate agent in plan: " + n);
                }
                cleanStage.add(n);
            }
            if (!cleanStage.isEmpty()) {
                cleaned.add(List.copyOf(cleanStage));
            }
        }
        for (String required : REQUIRED) {
            if (!seen.contains(required)) {
                throw new IllegalArgumentException("plan must include " + required);
            }
        }
        int intakeStage = stageOf(cleaned, "intake");
        int complianceStage = stageOf(cleaned, "compliance");
        if (intakeStage != 0 || cleaned.get(0).size() != 1) {
            throw new IllegalArgumentException("intake must run alone and first");
        }
        if (complianceStage != cleaned.size() - 1 || cleaned.get(complianceStage).size() != 1) {
            throw new IllegalArgumentException("compliance must run alone and last");
        }
        return new ExecutionPlan(List.copyOf(cleaned), rationale, true);
    }
}
