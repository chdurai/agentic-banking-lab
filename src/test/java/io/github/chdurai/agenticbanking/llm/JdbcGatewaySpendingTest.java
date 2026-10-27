package io.github.chdurai.agenticbanking.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.cases.JdbcCaseStore;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class JdbcGatewaySpendingTest {
    @ParameterizedTest @ValueSource(strings = {"retry", "repair", "fallback"})
    void everyPhysicalAttemptIsDurablyDebitedIncludingRetriesRepairsAndFallback(String failure) {
        var jdbc = JdbcCaseSpendLedgerTest.database();
        var harness = new LlmGatewayTest.Harness("m1", "m2", p -> p);
        if (failure.equals("retry")) harness.model.behaviour = n -> {
            if (n < 3) throw new TransientAiException("503 test unavailable");
            return LlmGatewayTest.GOOD;
        };
        if (failure.equals("repair")) harness.model.behaviour = n -> n == 1 ? "not-json" : LlmGatewayTest.GOOD;
        if (failure.equals("fallback")) harness.model.failByModel = Map.of("m1", new NonTransientAiException("400 test invalid"));
        var budget = new CaseBudget(100_000, 0, 0);
        budget.bind(new JdbcCaseSpendLedger(jdbc), "case");
        try (var ignored = CallerContext.DEV.withBudget(budget).open()) {
            assertThat(harness.call().value().answer()).isEqualTo("ok");
        }
        int expected = failure.equals("retry") ? 3 : 2;
        assertThat(harness.model.calls).hasValue(expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_spend_attempt", Integer.class)).isEqualTo(expected);
        assertThat(jdbc.queryForObject("SELECT tokens_debited FROM case_spend_account", Long.class)).isEqualTo(budget.tokensUsed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_spend_attempt WHERE settled=FALSE", Integer.class))
                .isEqualTo(failure.equals("repair") ? 0 : expected - 1);
    }

    @Test void failedCaseRetryAfterRepositoryAndBudgetReconstructionCannotObtainAnotherEnvelope() {
        var jdbc = JdbcCaseSpendLedgerTest.database();
        var harness = new LlmGatewayTest.Harness("m1", "m2", p -> p);
        harness.model.behaviour = n -> { throw new TransientAiException("503 test unavailable"); };
        var properties = harness.props.withBudget(new LlmProperties.Budget(Map.of("loan", new LlmProperties.FlowBudget(4000, 0, 0))));
        var first = service(jdbc, properties);
        assertThatThrownBy(() -> first.runIdempotent("loan", "retry-key", "subject", Map.of("x", 1),
                LlmGatewayTest.Out.class, "done", () -> harness.call().value())).isInstanceOf(LlmException.class);
        int firstCalls = harness.model.calls.get();
        assertThat(firstCalls).isEqualTo(1);
        harness.model.behaviour = n -> LlmGatewayTest.GOOD;
        var reconstructed = service(jdbc, properties);
        assertThatThrownBy(() -> reconstructed.runIdempotent("loan", "retry-key", "subject", Map.of("x", 1),
                LlmGatewayTest.Out.class, "done", () -> harness.call().value())).isInstanceOf(LlmException.class);
        assertThat(harness.model.calls).hasValue(firstCalls);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_spend_account", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_spend_attempt", Integer.class)).isEqualTo(1);
    }

    private CaseService service(JdbcTemplate jdbc, LlmProperties properties) {
        var store = new JdbcCaseStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())));
        var service = new CaseService(store, new ObjectMapper());
        ReflectionTestUtils.setField(service, "spending",
                new CaseSpendingScopes(properties, Optional.of(new JdbcCaseSpendLedger(jdbc))));
        return service;
    }

    @Test void propagatedParallelContextSharesTheDurableAccountAndExplicitBudgetCannotBypassIt() throws Exception {
        var jdbc = JdbcCaseSpendLedgerTest.database();
        var harness = new LlmGatewayTest.Harness("m1", "m2", p -> p);
        var budget = new CaseBudget(100_000, 0, 0);
        budget.bind(new JdbcCaseSpendLedger(jdbc), "case");
        try (var ignored = CallerContext.DEV.withBudget(budget).open();
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(CallerContext.wrapCall(harness::call));
            var second = workers.submit(CallerContext.wrapCall(harness::call));
            assertThat(first.get().value().answer()).isEqualTo("ok");
            assertThat(second.get().value().answer()).isEqualTo("ok");
            assertThatThrownBy(() -> harness.gateway.call(AgentCallSpec.of("a", "s", "u", LlmGatewayTest.Out.class, 0)
                    .withBudget(CaseBudget.unlimited()))).isInstanceOf(LlmException.class).hasMessageContaining("cannot replace");
        }
        assertThat(harness.model.calls).hasValue(2);
        assertThat(jdbc.queryForObject("SELECT tokens_debited FROM case_spend_account", Long.class)).isEqualTo(30);
    }

    @Test void durableStorageOutageAndRestoredCostCapCannotDispatchToUnpricedModel() {
        var jdbc = JdbcCaseSpendLedgerTest.database();
        new JdbcCaseSpendLedger(jdbc).open("case", new CaseSpendLedger.Limits(0, 100));
        var budget = CaseBudget.unlimited();
        budget.bind(new JdbcCaseSpendLedger(jdbc), "case");
        var harness = new LlmGatewayTest.Harness("m1", "m2", p -> p);
        try (var ignored = CallerContext.DEV.withBudget(budget).open()) {
            assertThatThrownBy(harness::call).isInstanceOf(LlmException.class).hasMessageContaining("pricing");
        }
        assertThat(harness.model.calls).hasValue(0);
        var tokensOnly = new CaseBudget(100_000, 0, 0);
        tokensOnly.bind(new JdbcCaseSpendLedger(jdbc), "other-case");
        jdbc.execute("ALTER TABLE case_spend_attempt RENAME TO unavailable_attempt");
        try (var ignored = CallerContext.DEV.withBudget(tokensOnly).open()) {
            assertThatThrownBy(harness::call).isInstanceOf(LlmException.class).hasMessageContaining("admission unavailable");
        }
        assertThat(harness.model.calls).hasValue(0);
    }

    @ParameterizedTest @ValueSource(strings = {"quota", "kill-switch", "deadline"})
    void unsentAdmissionRefusalsRefundDurableDebitAndNeverCallTheProvider(String refusal) {
        var jdbc = JdbcCaseSpendLedgerTest.database();
        var harness = new LlmGatewayTest.Harness("m1", "m2", p -> p);
        var budget = new CaseBudget(100_000, 0, refusal.equals("deadline") ? 1000 : 0);
        budget.bind(new JdbcCaseSpendLedger(jdbc), "case");
        QuotaLimiter quota = tokens -> {
            if (refusal.equals("kill-switch")) harness.gateway.setKillSwitch(true);
            if (refusal.equals("deadline")) {
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(2))
                        .until(() -> budget.exhausted() != null);
            }
            return !refusal.equals("quota");
        };
        harness.gateway = new LlmGateway(() -> org.springframework.ai.chat.client.ChatClient.builder(harness.model),
                harness.agents, harness.props, new io.github.chdurai.agenticbanking.ops.AgentMetrics(harness.registry, harness.agents),
                new io.github.chdurai.agenticbanking.ops.PlatformEventPublisher(event -> {}), new PromptRegistry(), quota,
                java.util.List.of(), harness.registry);
        try (var ignored = CallerContext.DEV.withBudget(budget).open()) {
            assertThatThrownBy(harness::call).isInstanceOfSatisfying(LlmException.class, failure ->
                    assertThat(failure.kind()).isEqualTo(switch (refusal) {
                        case "quota" -> LlmException.Kind.QUOTA;
                        case "kill-switch" -> LlmException.Kind.KILL_SWITCH;
                        default -> LlmException.Kind.BUDGET;
                    }));
        }
        assertThat(harness.model.calls).hasValue(0);
        assertThat(budget.tokensUsed()).isZero();
        assertThat(jdbc.queryForObject("SELECT tokens_debited FROM case_spend_account", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_spend_attempt WHERE settled=TRUE", Integer.class)).isEqualTo(1);
    }

    @Test void expiredSharedRecoveryPermitRefundsCaseDebitRatherThanDispatchingAfterAQuotaWait() {
        var jdbc = JdbcCaseSpendLedgerTest.database();
        var time = new java.util.concurrent.atomic.AtomicLong(1000);
        var config = new ProviderErrorBudgetProperties(true, 50, 2, 2, 100, 1, true);
        var shared = new JdbcProviderHealthBudget(jdbc, "test-provider", config, 50, time::get);
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> shared.execute(() -> { throw new TransientAiException("503 outage"); }))
                    .isInstanceOf(TransientAiException.class);
        }
        time.addAndGet(100);
        var harness = new LlmGatewayTest.Harness("m1", "m2", p -> p);
        harness.gateway = new LlmGateway(() -> org.springframework.ai.chat.client.ChatClient.builder(harness.model),
                harness.agents, harness.props, new io.github.chdurai.agenticbanking.ops.AgentMetrics(harness.registry, harness.agents),
                new io.github.chdurai.agenticbanking.ops.PlatformEventPublisher(event -> {}), new PromptRegistry(),
                tokens -> { time.addAndGet(50); return true; }, java.util.List.of(), harness.registry);
        harness.gateway.configureSharedProviderHealth(config, Optional.of(shared));
        var budget = new CaseBudget(100_000, 0, 0);
        budget.bind(new JdbcCaseSpendLedger(jdbc), "case");
        try (var ignored = CallerContext.DEV.withBudget(budget).open()) {
            assertThatThrownBy(harness::call).isInstanceOf(LlmException.class).hasMessageContaining("admission expired");
        }
        assertThat(harness.model.calls).hasValue(0);
        assertThat(jdbc.queryForObject("SELECT tokens_debited FROM case_spend_account", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_spend_attempt WHERE settled=TRUE", Integer.class)).isEqualTo(1);
        assertThat(shared.snapshot()).containsEntry("state", "OPEN");
    }
}
