package io.github.chdurai.agenticbanking.ops;

import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.memory.InMemoryCaseMemoryStore;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelMonitorTest {

    private final InMemoryCaseMemoryStore memory = new InMemoryCaseMemoryStore();
    private final AgentMetrics metrics = new AgentMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final ModelMonitor monitor = new ModelMonitor(memory, new ApprovalQueue(metrics));
    private int n;

    private void add(LoanDecision.Decision d, int count) {
        for (int i = 0; i < count; i++) {
            memory.record(new LoanDecision("A" + n++, d, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(),
                    "r", List.of(), 0, Instant.now().plusMillis(n)));
        }
    }

    @Test
    void reportsInsufficientDataForSmallSamples() {
        add(LoanDecision.Decision.APPROVED, 5);
        Map<String, Object> r = monitor.report(50);
        assertThat(((Map<?, ?>) r.get("drift")).get("status")).isEqualTo("INSUFFICIENT_DATA");
        assertThat(r.get("totalDecisions")).isEqualTo(5);
    }

    @Test
    void stableWhenRecentWindowMatchesBaseline() {
        for (int i = 0; i < 40; i++) {
            add(i % 2 == 0 ? LoanDecision.Decision.APPROVED : LoanDecision.Decision.DECLINED, 1);
        }
        Map<?, ?> drift = (Map<?, ?>) monitor.report(20).get("drift");
        assertThat(drift.get("status")).isEqualTo("STABLE");
    }

    @Test
    void flagsDriftWhenApprovalShareCollapses() {
        add(LoanDecision.Decision.APPROVED, 40);
        add(LoanDecision.Decision.DECLINED, 25);
        Map<?, ?> drift = (Map<?, ?>) monitor.report(25).get("drift");
        assertThat(drift.get("status")).isEqualTo("DRIFT_SUSPECTED");
        assertThat((List<?>) drift.get("alerts")).isNotEmpty();
    }
}
