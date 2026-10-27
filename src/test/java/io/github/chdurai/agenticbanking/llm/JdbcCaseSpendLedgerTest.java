package io.github.chdurai.agenticbanking.llm;

import io.github.chdurai.agenticbanking.cases.SchemaMigrator;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class JdbcCaseSpendLedgerTest {
    static JdbcTemplate database() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:spend" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        var jdbc = new JdbcTemplate(ds);
        SchemaMigrator.migrate(jdbc);
        return jdbc;
    }

    static CaseSpendLedger.Limits limits(long tokens, long micros) { return new CaseSpendLedger.Limits(tokens, micros); }

    @Test void independentReplicasCannotOversubscribeAndReconstructionDoesNotResetSpend() throws Exception {
        var jdbc = database();
        var accepted = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) tasks.add(workers.submit(() -> {
                var account = new JdbcCaseSpendLedger(jdbc).open("case", limits(100, 100));
                try { account.reserve(5, 5); accepted.incrementAndGet(); }
                catch (LlmException denied) { assertThat(denied.kind()).isEqualTo(LlmException.Kind.BUDGET); }
            }));
            for (var task : tasks) task.get();
        }
        assertThat(accepted).hasValue(20);
        assertThat(jdbc.queryForObject("SELECT tokens_debited FROM case_spend_account", Long.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_spend_attempt WHERE settled=FALSE", Integer.class)).isEqualTo(20);
        var reconstructed = new JdbcCaseSpendLedger(jdbc).open("case", limits(1000, 1000));
        assertThat(reconstructed.limits()).isEqualTo(limits(100, 100));
        assertThatThrownBy(() -> reconstructed.reserve(1, 0)).isInstanceOf(LlmException.class);
    }

    @Test void usageSettlesExactlyOnceCancellationRefundsAndProviderOveragesBlockFutureAdmission() throws Exception {
        var jdbc = database();
        var account = new JdbcCaseSpendLedger(jdbc).open("case", limits(100, 100));
        var known = account.reserve(60, 60);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = workers.submit(() -> known.settle(10, 20));
            var b = workers.submit(() -> known.settle(10, 20));
            a.get(); b.get();
        }
        assertThatThrownBy(() -> known.settle(0, 0)).isInstanceOf(IllegalStateException.class);
        var unsent = account.reserve(50, 50);
        unsent.settle(0, 0);
        assertThat(jdbc.queryForObject("SELECT tokens_debited FROM case_spend_account", Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT cost_debited_micros FROM case_spend_account", Long.class)).isEqualTo(20);
        account.reserve(30, 30).settle(120, 200);
        assertThat(jdbc.queryForObject("SELECT tokens_debited FROM case_spend_account", Long.class)).isEqualTo(130);
        assertThatThrownBy(() -> account.reserve(1, 0)).isInstanceOf(LlmException.class);
    }

    @Test void outerRollbackCannotRefundReservationsOrKnownUsage() {
        var jdbc = database();
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status -> {
            var account = new JdbcCaseSpendLedger(jdbc).open("case", limits(100, -1));
            account.reserve(60, 0);
            account.reserve(30, 0).settle(20, 0);
            status.setRollbackOnly();
        });
        var retry = new JdbcCaseSpendLedger(jdbc).open("case", limits(100, -1));
        retry.reserve(20, 0);
        assertThatThrownBy(() -> retry.reserve(1, 0)).isInstanceOf(LlmException.class);
    }

    @Test void tenantsAreIsolatedAndCapturedAccountCannotBeRescopedByAnotherCaller() {
        var jdbc = database();
        var ledger = new JdbcCaseSpendLedger(jdbc);
        CaseSpendLedger.Account first;
        try (var ignored = new CallerContext("first", "actor", Set.of()).open()) {
            first = ledger.open("same-case", limits(10, -1));
        }
        try (var ignored = new CallerContext("second", "actor", Set.of()).open()) {
            first.reserve(10, 0);
            ledger.open("same-case", limits(10, -1)).reserve(10, 0);
            assertThatThrownBy(() -> first.reserve(1, 0)).isInstanceOf(LlmException.class);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_spend_account WHERE tokens_debited=10", Integer.class)).isEqualTo(2);
    }

    @Test void tighteningCannotBeLoosenedAndAlreadyOpenReplicasObserveIt() {
        var jdbc = database();
        var ledger = new JdbcCaseSpendLedger(jdbc);
        var stale = ledger.open("case", limits(0, -1));
        stale.reserve(20, 10);
        ledger.open("case", limits(50, 30));
        assertThatThrownBy(() -> stale.reserve(1, 0, false)).isInstanceOf(LlmException.class).hasMessageContaining("pricing");
        assertThatThrownBy(() -> stale.reserve(31, 0)).isInstanceOf(LlmException.class);
        assertThatThrownBy(() -> stale.reserve(1, 21)).isInstanceOf(LlmException.class);
        stale.reserve(30, 20);
        assertThat(ledger.open("case", limits(0, -1)).limits()).isEqualTo(limits(50, 30));
    }

    @Test void budgetRetainsAmbiguousDebitsAcrossExecutionsAndRestoresPricingRequirement() {
        var jdbc = database();
        var first = new CaseBudget(100, .0001, 0);
        first.bind(new JdbcCaseSpendLedger(jdbc), "case");
        try (var unknown = first.reserve(60, .00006)) {}
        var retry = CaseBudget.unlimited();
        retry.bind(new JdbcCaseSpendLedger(jdbc), "case");
        assertThat(retry.costLimited()).isTrue();
        assertThatThrownBy(() -> retry.reserve(41, 0)).isInstanceOf(LlmException.class);
        try (var cancelled = retry.reserve(40, .00004)) { cancelled.cancel(); }
        try (var known = retry.reserve(40, .00004)) { known.settle(10, .00001); }
        assertThat(jdbc.queryForObject("SELECT tokens_debited FROM case_spend_account", Long.class)).isEqualTo(70);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM case_spend_attempt WHERE settled=FALSE", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> retry.bind(new JdbcCaseSpendLedger(jdbc), "another-case")).isInstanceOf(IllegalStateException.class);
    }

    @Test void subMicroDollarLimitIsNotMistakenForUnlimited() {
        var jdbc = database();
        var budget = new CaseBudget(0, .0000001, 0);
        budget.bind(new JdbcCaseSpendLedger(jdbc), "case");
        assertThatThrownBy(() -> budget.reserve(1, .0000001)).isInstanceOf(LlmException.class);
        var retry = CaseBudget.unlimited();
        retry.bind(new JdbcCaseSpendLedger(jdbc), "case");
        assertThat(retry.costLimited()).isTrue();
        assertThatThrownBy(() -> retry.reserve(1, .000001)).isInstanceOf(LlmException.class);
    }

    @Test void databaseOutageFailsAdmissionAndSettlementWithoutRefundingUnknownWork() {
        var jdbc = database();
        var budget = new CaseBudget(100, 0, 0);
        budget.bind(new JdbcCaseSpendLedger(jdbc), "case");
        try (var reservation = budget.reserve(60, 0)) {
            jdbc.execute("ALTER TABLE case_spend_attempt RENAME TO unavailable_attempt");
            assertThatThrownBy(() -> reservation.settle(10, 0)).isInstanceOf(LlmException.class).hasMessageContaining("settlement unavailable");
        }
        assertThat(budget.tokensUsed()).isEqualTo(60);
        assertThatThrownBy(() -> budget.reserve(10, 0)).isInstanceOf(LlmException.class).hasMessageContaining("admission unavailable");
        assertThat(jdbc.queryForObject("SELECT tokens_debited FROM case_spend_account", Long.class)).isEqualTo(60);
        jdbc.execute("DROP TABLE unavailable_attempt");
        jdbc.execute("DROP TABLE case_spend_account");
        assertThatThrownBy(() -> CaseBudget.unlimited().bind(new JdbcCaseSpendLedger(jdbc), "case"))
                .isInstanceOf(LlmException.class).hasMessageContaining("storage unavailable");
    }
}
