package io.github.chdurai.agenticbanking.orchestrator;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionPlanTest {

    private static final Set<String> KNOWN = Set.of("intake", "kyc", "credit-risk", "compliance");

    @Test
    void validPlanAccepted() {
        ExecutionPlan plan = ExecutionPlan.validate(
                List.of(List.of("intake"), List.of("kyc", "credit-risk"), List.of("compliance")),
                "standard", KNOWN);
        assertThat(plan.stages()).hasSize(3);
        assertThat(plan.stages().get(1)).containsExactlyInAnyOrder("kyc", "credit-risk");
        assertThat(plan.plannerGenerated()).isTrue();
    }

    @Test
    void reducedPlanAcceptedWhenRegulatoryAgentsPresent() {
        ExecutionPlan plan = ExecutionPlan.validate(
                List.of(List.of("intake"), List.of("kyc"), List.of("compliance")), "minimal", KNOWN);
        assertThat(plan.stages()).hasSize(3);
    }

    @Test
    void unknownAgentRejected() {
        assertThatThrownBy(() -> ExecutionPlan.validate(
                List.of(List.of("intake"), List.of("evil-agent")), "bad", KNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown agent");
    }

    @Test
    void duplicateAgentRejected() {
        assertThatThrownBy(() -> ExecutionPlan.validate(
                List.of(List.of("intake"), List.of("kyc"), List.of("kyc")), "dup", KNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void missingIntakeRejected() {
        assertThatThrownBy(() -> ExecutionPlan.validate(
                List.of(List.of("kyc")), "no intake", KNOWN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("intake");
    }

    @Test
    void emptyPlanRejected() {
        assertThatThrownBy(() -> ExecutionPlan.validate(List.of(), "empty", KNOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultPlanIsValid() {
        ExecutionPlan plan = ExecutionPlan.defaultPlan();
        assertThat(ExecutionPlan.validate(plan.stages(), plan.rationale(), KNOWN)).isNotNull();
        assertThat(plan.plannerGenerated()).isFalse();
    }
}
