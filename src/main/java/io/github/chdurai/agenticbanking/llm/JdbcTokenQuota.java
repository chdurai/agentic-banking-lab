package io.github.chdurai.agenticbanking.llm;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Atomic estimated-token admission shared by replicas using the same provider account and database. */
public final class JdbcTokenQuota implements QuotaLimiter {
    private final JdbcTemplate jdbc;
    private final String provider;
    private final long tpm;
    private final LongSupplier epochSeconds;
    private final org.springframework.transaction.support.TransactionTemplate independent;
    private final AtomicLong lastCleanup = new AtomicLong(-1);

    public JdbcTokenQuota(JdbcTemplate jdbc, LlmProperties props) {
        this(jdbc, props.provider(), props.resilience().quota().tpm(),
                () -> java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class))
                        .toInstant().getEpochSecond());
    }

    JdbcTokenQuota(JdbcTemplate jdbc, String provider, long tpm, LongSupplier epochSeconds) {
        if (provider == null || provider.isBlank() || provider.length() > 128 || tpm < 0) {
            throw new IllegalArgumentException("Invalid shared quota configuration");
        }
        this.jdbc = jdbc;
        this.provider = provider;
        this.tpm = tpm;
        this.epochSeconds = epochSeconds;
        this.independent = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(jdbc.getDataSource())));
        independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Override public boolean tryAcquire(long estimatedTokens) {
        if (estimatedTokens < 1) throw new IllegalArgumentException("Token reservation must be positive");
        if (tpm == 0) return true;
        if (estimatedTokens > tpm) return false;
        return java.util.Objects.requireNonNull(independent.execute(status -> reserve(estimatedTokens)));
    }

    private boolean reserve(long estimatedTokens) {
        long window = Math.floorDiv(epochSeconds.getAsLong(), 60);
        try {
            jdbc.update("INSERT INTO provider_quota (provider, window_start, reserved_tokens) VALUES (?,?,0)", provider, window);
        } catch (DuplicateKeyException existing) {
            // Another replica owns the same window; admission is the conditional update, not this insert.
        }
        boolean admitted = jdbc.update("""
                UPDATE provider_quota SET reserved_tokens=reserved_tokens+?
                WHERE provider=? AND window_start=? AND reserved_tokens<=?
                """, estimatedTokens, provider, window, tpm - estimatedTokens) == 1;
        if (lastCleanup.getAndSet(window) != window) {
            jdbc.update("DELETE FROM provider_quota WHERE provider=? AND window_start<?", provider, window - 60);
        }
        return admitted;
    }
}
