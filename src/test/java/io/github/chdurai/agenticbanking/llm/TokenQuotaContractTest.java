package io.github.chdurai.agenticbanking.llm;

import io.github.chdurai.agenticbanking.cases.SchemaMigrator;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class TokenQuotaContractTest {
    private JdbcTemplate database() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:quota" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(ds);
        SchemaMigrator.migrate(jdbc);
        return jdbc;
    }

    @Test void replicasShareAnExactReservationLimitAndCannotOversubscribeIt() throws Exception {
        var jdbc = database();
        var time = new AtomicLong(120);
        var first = new JdbcTokenQuota(jdbc, "shared-provider", 100, time::get);
        var second = new JdbcTokenQuota(jdbc, "shared-provider", 100, time::get);
        var accepted = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 40; i++) {
                QuotaLimiter limiter = i % 2 == 0 ? first : second;
                futures.add(workers.submit(() -> { if (limiter.tryAcquire(5)) accepted.incrementAndGet(); }));
            }
            for (var future : futures) future.get();
        }
        assertThat(accepted).hasValue(20);
        assertThat(jdbc.queryForObject("SELECT reserved_tokens FROM provider_quota", Long.class)).isEqualTo(100);
        assertThat(new JdbcTokenQuota(jdbc, "shared-provider", 100, time::get).tryAcquire(1)).isFalse();
        time.set(180);
        assertThat(second.tryAcquire(100)).isTrue();
        assertThat(first.tryAcquire(1)).isFalse();
        assertThat(new JdbcTokenQuota(jdbc, "other-provider", 100, time::get).tryAcquire(100)).isTrue();
    }

    @Test void rollingBackTheCallerCannotRefundAProviderAdmission() {
        var jdbc = database();
        var quota = new JdbcTokenQuota(jdbc, "provider", 100, () -> 120);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status -> {
            assertThat(quota.tryAcquire(60)).isTrue();
            assertThat(quota.tryAcquire(10)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(quota.tryAcquire(30)).isTrue();
        assertThat(quota.tryAcquire(1)).isFalse();
    }

    @Test void oversizedRequestsCannotBypassEitherLimiterOnAnEmptyWindow() {
        var local = new LocalTokenQuota(100, () -> 0);
        var shared = new JdbcTokenQuota(database(), "provider", 100, () -> 120);
        for (var quota : java.util.List.of(local, shared)) {
            assertThat(quota.tryAcquire(101)).isFalse();
            assertThat(quota.tryAcquire(100)).isTrue();
            assertThat(quota.tryAcquire(1)).isFalse();
            assertThatThrownBy(() -> quota.tryAcquire(0)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
