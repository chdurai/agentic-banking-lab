package io.github.chdurai.agenticbanking.cases;

import io.github.chdurai.agenticbanking.controller.FlowBridgeController;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class FlowBridgeContractTest {
    @Test void everyCaseStateHasAnExplicitExternalTaskState() {
        var expected = Map.of(
                CaseStatus.OPEN, "submitted",
                CaseStatus.RUNNING, "working",
                CaseStatus.PENDING_APPROVAL, "input-required",
                CaseStatus.COMPLETED, "completed",
                CaseStatus.APPROVED, "completed",
                CaseStatus.BLOCKED, "rejected",
                CaseStatus.REJECTED, "rejected",
                CaseStatus.EXPIRED, "canceled",
                CaseStatus.FAILED, "failed",
                CaseStatus.EXECUTION_FAILED, "failed");
        assertThat(expected.keySet()).containsExactlyInAnyOrder(CaseStatus.values());
        expected.forEach((status, external) -> assertThat(FlowBridgeController.state(status)).isEqualTo(external));
    }
}
