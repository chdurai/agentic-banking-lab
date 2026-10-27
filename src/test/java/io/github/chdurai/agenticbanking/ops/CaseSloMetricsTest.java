package io.github.chdurai.agenticbanking.ops;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.github.chdurai.agenticbanking.cases.CaseRecord;
import io.github.chdurai.agenticbanking.cases.CaseStatus;
import io.github.chdurai.agenticbanking.cases.InMemoryCaseStore;
import io.github.chdurai.agenticbanking.security.CallerContext;
import io.github.chdurai.agenticbanking.security.CallerProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CaseSloMetricsTest {
    @Test void queueMetricAggregatesTenantsOnceAndRestoresCaller() {
        var store = new InMemoryCaseStore();
        var callers = new CallerProperties(true, Map.of(
                "a", credential("a", "first-test-token-not-a-secret"),
                "a2", credential("a", "second-test-token-not-a-secret"),
                "b", credential("b", "third-test-token-not-a-secret")), Set.of());
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new CaseSloMetrics(registry, store, callers);
            for (String tenant : List.of("a", "b")) {
                try (var ignored = new CallerContext(tenant, "tester", Set.of("PLATFORM_ADMIN")).open()) {
                    store.enqueue("loan", "one", "hash", "sub", "{}");
                    if (tenant.equals("a")) store.enqueue("loan", "two", "hash", "sub", "{}");
                    store.claim("loan", "synchronous-not-queued", "hash", "sub");
                }
            }
            assertThat(registry.get("case.queue.depth").gauge().value()).isEqualTo(3);
            assertThat(CallerContext.current()).isEqualTo(CallerContext.DEV);
            assertThat(registry.scrape()).contains("case_queue_depth 3.0").doesNotContain("tenant", "tester");
        } finally { registry.close(); }
    }

    @Test void histogramsIncludeQueueTimeAndExposeTheSpecifiedFlowTarget() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var metrics = new CaseSloMetrics(registry, new InMemoryCaseStore(),
                    new CallerProperties(false, Map.of(), Set.of()));
            Instant start = Instant.parse("2026-01-01T00:00:00Z");
            metrics.processed(new CaseRecord("private-case-id", "loan", "private-subject", "key", "hash",
                    CaseStatus.PENDING_APPROVAL, 2, null, start, start.plusSeconds(25)));
            var timer = registry.get("case.processing").tag("flow", "loan")
                    .tag("status", "PENDING_APPROVAL").timer();
            assertThat(timer.count()).isEqualTo(1);
            assertThat(timer.totalTime(TimeUnit.SECONDS)).isEqualTo(25);
            assertThat(timer.takeSnapshot().histogramCounts()).anySatisfy(bucket -> {
                assertThat(bucket.bucket(TimeUnit.SECONDS)).isEqualTo(20);
                assertThat(bucket.count()).isZero();
            });
            assertThat(registry.scrape()).contains("case_processing_seconds_bucket", "le=\"20.0\"")
                    .doesNotContain("private-case-id", "private-subject");
        } finally { registry.close(); }
    }

    private CallerProperties.Credential credential(String tenant, String secret) {
        return new CallerProperties.Credential(secret, tenant, "operator", List.of("PLATFORM_ADMIN"));
    }

    @Test void unavailableQueueStorageIsNotMisreportedAsAnEmptyQueue() {
        var store = org.mockito.Mockito.mock(io.github.chdurai.agenticbanking.cases.CaseStore.class);
        org.mockito.Mockito.when(store.countQueued()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            new CaseSloMetrics(registry, store, new CallerProperties(false, Map.of(), Set.of()));
            assertThat(registry.get("case.queue.depth").gauge().value()).isNaN();
            assertThat(CallerContext.current()).isEqualTo(CallerContext.DEV);
        } finally { registry.close(); }
    }
}
