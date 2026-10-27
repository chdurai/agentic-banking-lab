package io.github.chdurai.agenticbanking.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class CaseBudgetReservationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void parallelAgentsCannotReserveMoreThanTheSharedEnvelope(boolean costLimited) throws Exception {
        var budget = costLimited ? new CaseBudget(0, .0001, 0) : new CaseBudget(100, 0, 0);
        var accepted = new AtomicInteger();
        var denied = new AtomicInteger();
        var attempted = new CountDownLatch(16);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++) tasks.add(workers.submit(() -> {
                CaseBudget.Reservation reservation;
                try { reservation = budget.reserve(10, costLimited ? .00001 : 0); }
                catch (LlmException e) {
                    assertThat(e.kind()).isEqualTo(LlmException.Kind.BUDGET);
                    denied.incrementAndGet();
                    attempted.countDown();
                    return;
                }
                try (reservation) {
                    accepted.incrementAndGet();
                    attempted.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release reservations");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            try {
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(accepted).hasValue(10);
                assertThat(denied).hasValue(6);
            } finally { release.countDown(); }
            for (var task : tasks) task.get();
        }
        assertThat(budget.tokensUsed()).isEqualTo(100);
        assertThat(budget.costUsd()).isEqualTo(costLimited ? .0001 : 0);
        assertThatThrownBy(() -> budget.reserve(1, costLimited ? .000001 : 0)).isInstanceOf(LlmException.class);
    }

    @Test void knownUsageSettlesWhileAmbiguousCallsRetainTheirFullReservation() {
        var budget = new CaseBudget(100, .001, 0);
        try (var first = budget.reserve(60, .0006)) {
            assertThatThrownBy(() -> budget.reserve(50, 0)).isInstanceOf(LlmException.class);
            assertThatThrownBy(() -> budget.reserve(1, .0005)).isInstanceOf(LlmException.class);
            first.settle(20, .0004);
        }
        try (var unknown = budget.reserve(80, .0006)) {
            assertThat(budget.tokensUsed()).isEqualTo(20);
        }
        assertThat(budget.tokensUsed()).isEqualTo(100);
        assertThat(budget.costUsd()).isEqualTo(.001);
    }

    @Test void cancellingUnsentWorkDoesNotConsumeBudget() {
        var budget = new CaseBudget(100, 0, 0);
        try (var cancelled = budget.reserve(100, 0)) { cancelled.cancel(); }
        assertThat(budget.tokensUsed()).isZero();
        try (var accepted = budget.reserve(100, 0)) { accepted.settle(5, 0); }
        assertThat(budget.tokensUsed()).isEqualTo(5);
        assertThatThrownBy(() -> budget.reserve(96, 0)).isInstanceOf(LlmException.class);
    }

    @Test void providerOveragesAreRecordedAndPreventFurtherAdmission() {
        var budget = new CaseBudget(100, .001, 0);
        try (var reservation = budget.reserve(20, .0002)) { reservation.settle(120, .002); }
        assertThat(budget.tokensUsed()).isEqualTo(120);
        assertThat(budget.costUsd()).isEqualTo(.002);
        assertThatThrownBy(() -> budget.reserve(1, 0)).isInstanceOf(LlmException.class);
    }

    @Test void extremeValidDurationDoesNotOverflowTheDeadline() {
        var budget = new CaseBudget(0, 0, Long.MAX_VALUE / 1_000_000);
        assertThat(budget.exhausted()).isNull();
        assertThat(budget.remainingMillis()).isPositive();
        assertThatThrownBy(() -> new CaseBudget(0, Double.NaN, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseBudget(0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseBudget(0, 0, Long.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmProperties.FlowBudget(0, 0, Long.MAX_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nestedFlowScopesReuseTheOriginalEnvelopeAndRestoreTheCaller() {
        var properties = LlmProperties.defaults().budget();
        var budget = new CaseBudget(100, 0, 0);
        try (var original = io.github.chdurai.agenticbanking.security.CallerContext.DEV.withBudget(budget).open()) {
            try (var scope = properties.openForFlow("loan")) {
                assertThat(io.github.chdurai.agenticbanking.security.CallerContext.current().budget()).isSameAs(budget);
                budget.charge(10, 0);
            }
            assertThat(io.github.chdurai.agenticbanking.security.CallerContext.current().budget()).isSameAs(budget);
            assertThat(budget.tokensUsed()).isEqualTo(10);
        }
        assertThat(io.github.chdurai.agenticbanking.security.CallerContext.current()).isEqualTo(io.github.chdurai.agenticbanking.security.CallerContext.DEV);
    }
}
