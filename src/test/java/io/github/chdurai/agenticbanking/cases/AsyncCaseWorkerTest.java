package io.github.chdurai.agenticbanking.cases;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.approval.InMemoryApprovalStore;
import io.github.chdurai.agenticbanking.llm.LlmProperties;
import io.github.chdurai.agenticbanking.security.CallerProperties;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncCaseWorkerTest {
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();

    private AsyncCaseService worker(CaseStore store, FlowCaseDispatcher dispatcher) {
        return new AsyncCaseService(store, new ObjectMapper(), dispatcher, new CallerProperties(false, Map.of(), Set.of()),
                new InMemoryApprovalStore(), LlmProperties.defaults(),
                new io.github.chdurai.agenticbanking.ops.CaseSloMetrics(registry,
                        store, new CallerProperties(false, Map.of(), Set.of())), 1, 1000, true);
    }

    @Test void failedWorkIsNotAutomaticallyReplayed() {
        var store = new InMemoryCaseStore();
        var dispatcher = mock(FlowCaseDispatcher.class);
        when(dispatcher.execute(anyString(), anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        var row = store.enqueue("document", "failure", "hash", "sub", "{}").record();
        var worker = worker(store, dispatcher);
        try {
            worker.drain();
            await().untilAsserted(() -> assertThat(store.find(row.caseId()).orElseThrow().status()).isEqualTo(CaseStatus.FAILED));
            await().untilAsserted(() -> assertThat(registry.get("case.processing")
                    .tag("status", "FAILED").timer().count()).isEqualTo(1));
            worker.drain();
            verify(dispatcher, times(1)).execute("document", "{}");
        } finally { worker.close(); }
    }

    @Test void saturationLeavesWorkDurableUntilAnExecutionSlotIsFree() throws Exception {
        var store = new InMemoryCaseStore();
        var dispatcher = mock(FlowCaseDispatcher.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(dispatcher.execute(anyString(), anyString())).thenAnswer(call -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release worker");
            assertThat(io.github.chdurai.agenticbanking.security.CallerContext.isBound()).isTrue();
            assertThat(io.github.chdurai.agenticbanking.security.CallerContext.current().budget()).isNotNull();
            return Map.of("status", "ANALYZED");
        });
        store.enqueue("document", "one", "hash", "sub", "{}");
        store.enqueue("document", "two", "hash", "sub", "{}");
        var worker = worker(store, dispatcher);
        try {
            worker.drain();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(store.running(10)).hasSize(1);
            assertThat(store.queued(10)).hasSize(1);
            release.countDown();
            await().untilAsserted(() -> assertThat(store.running(10)).isEmpty());
            await().untilAsserted(() -> {
                worker.drain();
                assertThat(store.queued(10)).isEmpty();
            });
            await().untilAsserted(() -> assertThat(store.running(10)).isEmpty());
            verify(dispatcher, times(2)).execute("document", "{}");
            await().untilAsserted(() -> assertThat(registry.get("case.processing")
                    .tag("status", "COMPLETED").timer().count()).isEqualTo(2));
        } finally { release.countDown(); worker.close(); }
    }

    @Test void expiredExecutionLeaseIsMarkedUncertainWithoutRepeatingSideEffects() {
        var store = spy(new InMemoryCaseStore());
        var dispatcher = mock(FlowCaseDispatcher.class);
        var row = store.enqueue("document", "orphan", "hash", "sub", "{}").record();
        var running = store.transition(row.caseId(), 0, CaseStatus.RUNNING, null, "old-worker", "started", null);
        var old = new CaseRecord(running.caseId(), running.flow(), running.subjectRef(), running.idempotencyKey(),
                running.requestHash(), running.status(), running.version(), null, running.createdAt(),
                Instant.now().minusSeconds(60), running.tenantId(), running.requestJson());
        doReturn(List.of(old)).when(store).running(100);
        var worker = worker(store, dispatcher);
        try {
            worker.drain();
            assertThat(store.find(row.caseId()).orElseThrow().status()).isEqualTo(CaseStatus.FAILED);
            assertThat(store.events(row.caseId())).extracting(CaseEvent::type).contains("case.execution-uncertain");
            verifyNoInteractions(dispatcher);
            assertThat(registry.get("case.processing").tag("status", "FAILED").timer().count()).isEqualTo(1);
        } finally { worker.close(); }
    }
}
