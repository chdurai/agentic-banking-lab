package io.github.chdurai.agenticbanking.security;

import io.github.chdurai.agenticbanking.llm.LlmProperties;
import io.github.chdurai.agenticbanking.memory.*;
import io.github.chdurai.agenticbanking.ops.BoundedHistory;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class TenantBoundariesTest {
    private final CallerContext alpha = new CallerContext("alpha", "a", Set.of("PLATFORM_ADMIN"));
    private final CallerContext beta = new CallerContext("beta", "b", Set.of("PLATFORM_ADMIN"));

    @Test void memoryErasureRetentionAndHistoryCapacityAreTenantScoped() {
        var retention = new RetentionProperties(7, 7, 7, 7,
                Map.of("alpha", new RetentionProperties.Windows(1, 1, 1, 1)));
        var memory = new EpisodicMemoryStore(retention, new MemorySanitizer());
        var history = new BoundedHistory<String>(1);
        try (var ignored = alpha.open()) {
            memory.recordIncident("shared-subject", "db", "timeouts", "alpha summary", true);
            history.add("alpha");
        }
        try (var ignored = beta.open()) {
            assertThat(memory.total()).isZero();
            assertThat(memory.eraseSubject("shared-subject")).isZero();
            memory.recordIncident("shared-subject", "db", "timeouts", "beta summary", true);
            history.add("beta old");
            history.add("beta new");
            assertThat(memory.purgeExpired(Instant.now().plus(2, ChronoUnit.DAYS))).isZero();
            assertThat(history.recent(5)).containsExactly("beta new");
        }
        try (var ignored = alpha.open()) {
            assertThat(history.recent(5)).containsExactly("alpha");
            assertThat(memory.similarIncidents("db", "timeouts", 5)).extracting(EpisodicMemoryStore.Entry::summary)
                    .containsExactly("alpha summary");
            assertThat(memory.purgeExpired(Instant.now().plus(2, ChronoUnit.DAYS))).isEqualTo(1);
        }
        try (var ignored = beta.open()) { assertThat(memory.total()).isEqualTo(1); }
    }

    @Test void tenantBudgetOverridesFallBackPerFlowAndSharedScopeCrossesExecutors() throws Exception {
        var budgets = new LlmProperties.Budget(Map.of("loan", new LlmProperties.FlowBudget(5, 0, 0),
                        "document", new LlmProperties.FlowBudget(10, 0, 0)),
                Map.of("alpha", Map.of("loan", new LlmProperties.FlowBudget(2, 0, 0))));
        try (var ignored = alpha.open()) {
            var loan = budgets.newCaseBudget("loan");
            var document = budgets.newCaseBudget("document");
            loan.charge(2, 0);
            document.charge(10, 0);
            assertThat(loan.exhausted()).contains("2/2");
            assertThat(document.exhausted()).contains("10/10");
            try (var scope = alpha.withBudget(document).open();
                 var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                assertThat(executor.submit(CallerContext.wrapCall(CallerContext::current)).get().budget()).isSameAs(document);
            }
        }
        try (var ignored = beta.open()) {
            var loan = budgets.newCaseBudget("loan");
            loan.charge(2, 0);
            assertThat(loan.exhausted()).isNull();
        }
        assertThat(CallerContext.isBound()).isFalse();
    }
}
