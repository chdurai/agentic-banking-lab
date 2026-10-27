package io.github.chdurai.agenticbanking.llm;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Count-window health samples and recovery-probe leases shared by a provider account's replicas. */
public final class JdbcProviderHealthBudget {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JdbcProviderHealthBudget.class);
    private enum Result { SUCCESS, FAILURE, NEUTRAL }
    private final JdbcTemplate jdbc;
    private final String provider;
    private final ProviderErrorBudgetProperties config;
    private final long probeLeaseMs;
    private final LongSupplier clock;
    private final TransactionTemplate atomic;
    private final TransactionTemplate independent;
    private final ThreadLocal<Permit> current = new ThreadLocal<>();

    public JdbcProviderHealthBudget(JdbcTemplate jdbc, LlmProperties properties, ProviderErrorBudgetProperties config) {
        this(jdbc, properties.provider(), config, properties.resilience().timeoutMs(), databaseClock(jdbc));
    }

    JdbcProviderHealthBudget(JdbcTemplate jdbc, String provider, ProviderErrorBudgetProperties config,
                             long probeLeaseMs, LongSupplier clock) {
        if (provider == null || provider.isBlank() || provider.length() > 128 || !config.enabled()
                || !config.shared() || probeLeaseMs < 1) {
            throw new IllegalArgumentException("Invalid shared provider health configuration");
        }
        this.jdbc = jdbc;
        this.provider = provider;
        this.config = config;
        this.probeLeaseMs = probeLeaseMs;
        this.clock = clock;
        var manager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        atomic = new TransactionTemplate(manager);
        atomic.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        String signature = config.failureRatePercent() + "/" + config.slidingWindowSize() + "/" + config.minimumCalls()
                + "/" + config.waitOpenMs() + "/" + config.halfOpenCalls() + "/" + probeLeaseMs;
        independent.executeWithoutResult(status -> {
            try {
                jdbc.update("""
                        INSERT INTO provider_health
                        (provider,configuration,circuit_state,generation,open_until_ms,probe_until_ms,
                         active_probes,successful_probes,sample_sequence) VALUES (?,?,'CLOSED',0,0,0,0,0,0)
                        """, provider, signature);
            } catch (DuplicateKeyException existing) {
                // Reconstruction must preserve a peer's open circuit and probe leases.
            }
            if (!signature.equals(jdbc.queryForObject("SELECT configuration FROM provider_health WHERE provider=?",
                    String.class, provider))) {
                throw new IllegalStateException("Shared provider health settings differ from the stored configuration");
            }
        });
    }

    public <T> T execute(Supplier<T> work) {
        try {
            Permit permit = acquire();
            Permit previous = current.get();
            current.set(permit);
            try {
                T result;
                try { result = work.get(); }
                catch (RuntimeException failure) {
                    try { permit.complete(LlmErrorClassifier.countsAsProviderFailure(failure) ? Result.FAILURE : Result.NEUTRAL); }
                    catch (DataAccessException storage) {
                        storage.addSuppressed(failure);
                        throw storage;
                    }
                    throw failure;
                }
                permit.complete(Result.SUCCESS);
                return result;
            } finally {
                if (previous == null) current.remove();
                else current.set(previous);
            }
        } catch (DataAccessException e) {
            throw new LlmException(LlmException.Kind.PROVIDER_BUDGET, "Shared provider health storage unavailable", e);
        }
    }

    /** Revalidate after spending/quota storage waits, before any provider submission. */
    public void checkDispatch() {
        Permit permit = current.get();
        if (permit == null) throw new IllegalStateException("Provider dispatch requires shared health admission");
        try {
            boolean admitted = Boolean.TRUE.equals(atomic.execute(status -> {
                State state = lock();
                if (state.generation != permit.generation) return false;
                if (permit.probe && state.name.equals("HALF_OPEN")) {
                    long now = clock.getAsLong();
                    if (now < state.probeUntil) return true;
                    open(state, now);
                    return false;
                }
                return !permit.probe && state.name.equals("CLOSED");
            }));
            if (!admitted) throw new LlmException(LlmException.Kind.PROVIDER_BUDGET, "Shared provider health admission expired");
        } catch (DataAccessException e) {
            throw new LlmException(LlmException.Kind.PROVIDER_BUDGET, "Shared provider health storage unavailable", e);
        }
    }

    final class Permit {
        private final long generation;
        private final boolean probe;
        private final AtomicBoolean completed = new AtomicBoolean();
        Permit(long generation, boolean probe) { this.generation = generation; this.probe = probe; }

        private void complete(Result result) {
            if (!completed.compareAndSet(false, true)) throw new IllegalStateException("Provider health permit already completed");
            atomic.executeWithoutResult(status -> {
                State state = lock();
                if (state.generation != generation || probe != state.name.equals("HALF_OPEN")) return;
                long now = clock.getAsLong();
                if (probe) {
                    if (now >= state.probeUntil || result == Result.FAILURE) {
                        open(state, now);
                    } else {
                        int active = state.active - 1;
                        int successes = state.successes + (result == Result.SUCCESS ? 1 : 0);
                        if (successes >= config.halfOpenCalls()) {
                            jdbc.update("""
                                    UPDATE provider_health SET circuit_state='CLOSED',generation=generation+1,
                                    active_probes=0,successful_probes=0,probe_until_ms=0,open_until_ms=0
                                    WHERE provider=?
                                    """, provider);
                            jdbc.update("DELETE FROM provider_health_sample WHERE provider=?", provider);
                        } else {
                            jdbc.update("""
                                    UPDATE provider_health SET active_probes=?,successful_probes=? WHERE provider=?
                                    """, active, successes, provider);
                        }
                    }
                } else if (state.name.equals("CLOSED") && result != Result.NEUTRAL) {
                    long sequence = Math.incrementExact(state.sequence);
                    jdbc.update("UPDATE provider_health SET sample_sequence=? WHERE provider=?", sequence, provider);
                    jdbc.update("INSERT INTO provider_health_sample (provider,sample_sequence,failed) VALUES (?,?,?)",
                            provider, sequence, result == Result.FAILURE);
                    jdbc.update("DELETE FROM provider_health_sample WHERE provider=? AND sample_sequence<=?",
                            provider, sequence - config.slidingWindowSize());
                    long total = count("");
                    long failed = count(" AND failed=TRUE");
                    if (total >= config.minimumCalls() && 100.0 * failed / total >= config.failureRatePercent()) {
                        open(state, now);
                    }
                }
            });
        }
    }

    Permit acquire() {
        // A refusal must not roll back the OPEN transition that records an expired ambiguous probe.
        Permit permit = atomic.execute(status -> {
            State state = lock();
            count("");
            long now = clock.getAsLong();
            if (state.name.equals("OPEN")) {
                if (now < state.openUntil) return null;
                jdbc.update("""
                        UPDATE provider_health SET circuit_state='HALF_OPEN',active_probes=0,
                        successful_probes=0,probe_until_ms=0 WHERE provider=?
                        """, provider);
                state = new State("HALF_OPEN", state.generation, 0, 0, 0, 0, state.sequence);
            }
            if (state.name.equals("HALF_OPEN")) {
                if (state.active > 0 && now >= state.probeUntil) {
                    open(state, now);
                    return null;
                }
                if (state.active + state.successes >= config.halfOpenCalls()) return null;
                long until = state.active == 0 ? after(now, probeLeaseMs) : state.probeUntil;
                jdbc.update("UPDATE provider_health SET active_probes=active_probes+1,probe_until_ms=? WHERE provider=?",
                        until, provider);
                return new Permit(state.generation, true);
            }
            return new Permit(state.generation, false);
        });
        if (permit == null) {
            throw new LlmException(LlmException.Kind.PROVIDER_BUDGET, "Shared provider health budget exhausted");
        }
        return permit;
    }

    public Map<String, Object> snapshot() {
        return Objects.requireNonNull(atomic.execute(status -> {
            State state = lock();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("enabled", true);
            view.put("shared", true);
            view.put("state", state.name);
            view.put("bufferedCalls", count(""));
            view.put("failedCalls", count(" AND failed=TRUE"));
            view.put("activeProbes", state.active);
            view.put("successfulProbes", state.successes);
            view.put("openUntilEpochMs", state.openUntil);
            view.put("probeLeaseUntilEpochMs", state.probeUntil);
            return Map.copyOf(view);
        }));
    }

    public double stateMetric() {
        try {
            String state = jdbc.queryForObject("SELECT circuit_state FROM provider_health WHERE provider=?", String.class, provider);
            return switch (Objects.requireNonNull(state)) {
                case "CLOSED" -> 0;
                case "HALF_OPEN" -> 1;
                case "OPEN" -> 2;
                default -> throw new DataRetrievalFailureException("Invalid shared provider health state");
            };
        } catch (DataAccessException e) {
            log.warn("Shared provider health metric unavailable for {}", provider, e);
            return Double.NaN;
        }
    }

    private void open(State state, long now) {
        jdbc.update("""
                UPDATE provider_health SET circuit_state='OPEN',generation=?,open_until_ms=?,
                probe_until_ms=0,active_probes=0,successful_probes=0 WHERE provider=?
                """, Math.incrementExact(state.generation), after(now, config.waitOpenMs()), provider);
    }

    private long count(String filter) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_health_sample WHERE provider=?" + filter, Long.class, provider));
    }

    private record State(String name, long generation, long openUntil, long probeUntil, int active,
                         int successes, long sequence) {}

    private State lock() {
        var rows = jdbc.query("""
                SELECT circuit_state,generation,open_until_ms,probe_until_ms,active_probes,successful_probes,sample_sequence
                FROM provider_health WHERE provider=? FOR UPDATE
                """, (r, n) -> new State(r.getString(1), r.getLong(2), r.getLong(3), r.getLong(4),
                r.getInt(5), r.getInt(6), r.getLong(7)), provider);
        if (rows.size() != 1) throw new DataRetrievalFailureException("Shared provider health account unavailable");
        return rows.getFirst();
    }

    private static long after(long now, long duration) {
        if (now < 0) throw new IllegalStateException("Database clock must not precede the Unix epoch");
        return duration > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + duration;
    }

    private static LongSupplier databaseClock(JdbcTemplate jdbc) {
        boolean postgres = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                connection -> connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")));
        String query = postgres ? "SELECT clock_timestamp()" : "SELECT CURRENT_TIMESTAMP";
        return () -> Objects.requireNonNull(jdbc.queryForObject(query, Timestamp.class)).getTime();
    }
}
