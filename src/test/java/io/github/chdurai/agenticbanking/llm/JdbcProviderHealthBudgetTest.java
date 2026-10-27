package io.github.chdurai.agenticbanking.llm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

class JdbcProviderHealthBudgetTest {
    private final JdbcTemplate jdbc = JdbcCaseSpendLedgerTest.database();
    private final AtomicLong clock = new AtomicLong(1000);
    private final ProviderErrorBudgetProperties config = new ProviderErrorBudgetProperties(true, 50, 4, 2, 100, 2, true);

    private JdbcProviderHealthBudget gate() { return gate("provider"); }
    private JdbcProviderHealthBudget gate(String provider) {
        return new JdbcProviderHealthBudget(jdbc, provider, config, 50, clock::get);
    }

    private void fail(JdbcProviderHealthBudget gate) {
        assertThatThrownBy(() -> gate.execute(() -> { throw new TransientAiException("503 synthetic outage"); }))
                .isInstanceOf(TransientAiException.class);
    }

    private void denied(JdbcProviderHealthBudget gate) {
        assertThatThrownBy(() -> gate.execute(() -> { throw new AssertionError("Provider must not be called"); }))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PROVIDER_BUDGET));
    }

    @Test void replicasAndReconstructionShareOpenCircuitWhileDifferentProvidersStayIndependent() {
        var first = gate();
        var peer = gate();
        fail(first); fail(first);
        denied(peer); denied(gate());
        assertThat(peer.snapshot()).containsEntry("state", "OPEN").containsEntry("bufferedCalls", 2L);
        assertThat(gate("other-provider").execute(() -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> new JdbcProviderHealthBudget(jdbc, "provider",
                new ProviderErrorBudgetProperties(true, 75, 4, 2, 100, 2, true), 50, clock::get))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("stored configuration");
    }

    @Test void recoveryProbeAllowanceIsAtomicAcrossAllReplicas() throws Exception {
        var first = gate();
        var second = gate();
        fail(first); fail(second);
        clock.addAndGet(100);
        var attempted = new CountDownLatch(40);
        var release = new CountDownLatch(1);
        var admitted = new AtomicInteger();
        var refused = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) {
                var replica = i % 2 == 0 ? first : second;
                tasks.add(workers.submit(() -> {
                    try {
                        replica.execute(() -> {
                            admitted.incrementAndGet();
                            attempted.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Probe not released");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(e);
                            }
                            return "ok";
                        });
                    } catch (LlmException denied) {
                        assertThat(denied.kind()).isEqualTo(LlmException.Kind.PROVIDER_BUDGET);
                        refused.incrementAndGet();
                        attempted.countDown();
                    }
                }));
            }
            try {
                assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(admitted).hasValue(2);
                assertThat(refused).hasValue(38);
                assertThat(gate().snapshot()).containsEntry("activeProbes", 2).containsEntry("state", "HALF_OPEN");
            } finally { release.countDown(); }
            for (var task : tasks) task.get();
        }
        assertThat(first.snapshot()).containsEntry("state", "CLOSED").containsEntry("bufferedCalls", 0L);
    }

    @Test void crashedProbeExpiresConservativelyAndItsLeaseCannotBeResetByReconstruction() {
        var first = gate();
        fail(first); fail(first);
        clock.addAndGet(100);
        first.acquire();
        assertThat(gate().snapshot()).containsEntry("activeProbes", 1);
        clock.addAndGet(50);
        denied(gate());
        assertThat(first.snapshot()).containsEntry("state", "OPEN").containsEntry("openUntilEpochMs", 1250L);
        clock.addAndGet(100);
        assertThat(gate().execute(() -> "ok")).isEqualTo("ok");
        assertThat(gate().snapshot()).containsEntry("state", "HALF_OPEN").containsEntry("successfulProbes", 1);
        assertThat(gate().execute(() -> "ok")).isEqualTo("ok");
        assertThat(gate().snapshot()).containsEntry("state", "CLOSED");
    }

    @Test void lateCompletionsFromAnOldGenerationCannotHealAnOpenCircuit() {
        var first = gate();
        var second = gate();
        assertThat(first.execute(() -> {
            fail(second); fail(second);
            return "late success";
        })).isEqualTo("late success");
        assertThat(first.snapshot()).containsEntry("state", "OPEN").containsEntry("bufferedCalls", 2L);
        denied(first);
        clock.addAndGet(100);
        first.execute(() -> {
            clock.addAndGet(50);
            denied(second);
            return "late probe";
        });
        assertThat(first.snapshot()).containsEntry("state", "OPEN");
    }

    @Test void policyRefusalsAreNeutralAndCannotHealTheHalfOpenCircuit() {
        var gate = gate();
        for (var kind : new LlmException.Kind[]{LlmException.Kind.BUDGET, LlmException.Kind.QUOTA,
                LlmException.Kind.KILL_SWITCH, LlmException.Kind.OUTPUT_INVALID}) {
            assertThatThrownBy(() -> gate.execute(() -> { throw new LlmException(kind, "synthetic refusal"); }))
                    .isInstanceOf(LlmException.class);
        }
        assertThat(gate.snapshot()).containsEntry("state", "CLOSED").containsEntry("bufferedCalls", 0L);
        fail(gate); fail(gate);
        clock.addAndGet(100);
        assertThatThrownBy(() -> gate.execute(() -> { throw new LlmException(LlmException.Kind.QUOTA, "quota"); }))
                .isInstanceOf(LlmException.class);
        assertThat(gate.snapshot()).containsEntry("state", "HALF_OPEN")
                .containsEntry("successfulProbes", 0).containsEntry("activeProbes", 0);
        gate.execute(() -> "ok");
        assertThat(gate.snapshot()).containsEntry("state", "HALF_OPEN");
        gate.execute(() -> "ok");
        assertThat(gate.snapshot()).containsEntry("state", "CLOSED");
    }

    @Test void countWindowIsBoundedAndUsesTheConfiguredFailureThreshold() {
        var gate = gate();
        for (int i = 0; i < 20; i++) gate.execute(() -> "ok");
        assertThat(gate.snapshot()).containsEntry("bufferedCalls", 4L).containsEntry("state", "CLOSED");
        fail(gate);
        assertThat(gate.snapshot()).containsEntry("failedCalls", 1L).containsEntry("state", "CLOSED");
        fail(gate);
        assertThat(gate.snapshot()).containsEntry("failedCalls", 2L).containsEntry("state", "OPEN");
    }

    @Test void callerRollbackCannotRefundHealthFailuresOrRecoveryProbeSuccesses() {
        var gate = gate();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status -> {
            fail(gate); fail(gate);
            status.setRollbackOnly();
        });
        assertThat(gate.snapshot()).containsEntry("state", "OPEN");
        clock.addAndGet(100);
        tx.executeWithoutResult(status -> {
            gate.execute(() -> "ok");
            status.setRollbackOnly();
        });
        assertThat(gate.snapshot()).containsEntry("successfulProbes", 1);
    }

    @Test void dispatchAfterAStorageWaitCannotUseAnExpiredProbeOrStaleClosedPermit() {
        var gate = gate();
        var peer = gate();
        var dispatched = new AtomicInteger();
        assertThatThrownBy(() -> gate.execute(() -> {
            fail(peer); fail(peer);
            gate.checkDispatch();
            dispatched.incrementAndGet();
            return "not sent";
        })).isInstanceOf(LlmException.class).hasMessageContaining("admission expired");
        clock.addAndGet(100);
        assertThatThrownBy(() -> gate.execute(() -> {
            clock.addAndGet(50);
            gate.checkDispatch();
            dispatched.incrementAndGet();
            return "not sent";
        })).isInstanceOf(LlmException.class).hasMessageContaining("admission expired");
        assertThat(dispatched).hasValue(0);
        assertThat(gate.snapshot()).containsEntry("state", "OPEN");
    }

    @Test void missingSampleStorageFailsAdmissionAndCompletionWithoutALocalFallback() {
        var gate = gate();
        assertThatThrownBy(() -> gate.execute(() -> {
            jdbc.execute("ALTER TABLE provider_health_sample RENAME TO unavailable_health_samples");
            return "provider completed";
        })).isInstanceOf(LlmException.class).hasMessageContaining("storage unavailable");
        assertThatThrownBy(() -> gate.execute(() -> { throw new AssertionError("Must not dispatch on missing storage"); }))
                .isInstanceOf(LlmException.class).hasMessageContaining("storage unavailable");
        jdbc.execute("ALTER TABLE unavailable_health_samples RENAME TO provider_health_sample");
        assertThat(gate.snapshot()).containsEntry("bufferedCalls", 0L);
        assertThat(gate.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test void sharedModeCannotSilentlyRunWithoutTheEnabledBudgetAndJdbcStore() {
        assertThatThrownBy(() -> new ProviderErrorBudgetProperties(false, 50, 4, 2, 100, 1, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enabled");
        var harness = new LlmGatewayTest.Harness("m1", "m2", p -> p);
        assertThatThrownBy(() -> harness.gateway.configureSharedProviderHealth(config, java.util.Optional.empty()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JDBC persistence");
    }

    @Test void sharedStateMetricDoesNotFabricateHealthyStateWhenTheDatabaseIsUnavailable() {
        var gate = gate();
        assertThat(gate.stateMetric()).isZero();
        fail(gate); fail(gate);
        assertThat(gate.stateMetric()).isEqualTo(2);
        clock.addAndGet(100);
        gate.acquire();
        assertThat(gate.stateMetric()).isEqualTo(1);
        jdbc.execute("ALTER TABLE provider_health RENAME TO unavailable_health");
        assertThat(gate.stateMetric()).isNaN();
        assertThatThrownBy(gate::snapshot).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
