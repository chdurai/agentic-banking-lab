package io.github.chdurai.agenticbanking.llm;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-case spending envelope (tokens, USD, wall clock) shared by every LLM call of one case.
 * Parallel agents reserve conservative estimates before each physical call. Failed/ambiguous calls
 * retain their reservation; known usage settles it. This is admission control, not a billing guarantee.
 */
public final class CaseBudget {

    private long maxTokens;
    private final double maxCostUsd;
    private final long startedNanos = System.nanoTime();
    private final long maxNanos;
    private final AtomicLong tokens = new AtomicLong();
    private final AtomicLong costMicroUsd = new AtomicLong();
    private long reservedTokens;
    private long reservedMicroUsd;
    private long durableCostLimit = -1;
    private CaseSpendLedger.Account durable;
    private String durableCaseId;

    synchronized void bind(CaseSpendLedger ledger, String caseId) {
        if (durable != null) {
            if (!durableCaseId.equals(io.github.chdurai.agenticbanking.security.CallerContext.key(caseId))) {
                throw new IllegalStateException("A spending envelope cannot be rebound to another durable case");
            }
            return;
        }
        try {
            durable = ledger.open(caseId, new CaseSpendLedger.Limits(maxTokens,
                    maxCostUsd > 0 ? (long) Math.floor(maxCostUsd * 1_000_000) : -1));
            durableCaseId = io.github.chdurai.agenticbanking.security.CallerContext.key(caseId);
            maxTokens = durable.limits().tokens();
            durableCostLimit = durable.limits().costMicros();
        } catch (org.springframework.dao.DataAccessException e) {
            throw new LlmException(LlmException.Kind.BUDGET, "Durable case spend storage unavailable", e);
        }
    }

    /** Zero means unlimited for that dimension. */
    public CaseBudget(long maxTokens, double maxCostUsd, long maxMillis) {
        validateLimits(maxTokens, maxCostUsd, maxMillis);
        this.maxTokens = maxTokens;
        this.maxCostUsd = maxCostUsd;
        this.maxNanos = maxMillis * 1_000_000;
    }

    static void validateLimits(long maxTokens, double maxCostUsd, long maxMillis) {
        if (maxTokens < 0 || !Double.isFinite(maxCostUsd) || maxCostUsd < 0
                || maxCostUsd > Long.MAX_VALUE / 1_000_000.0 || maxMillis < 0 || maxMillis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException("Invalid case spending envelope");
        }
    }

    public static CaseBudget unlimited() {
        return new CaseBudget(0, 0, 0);
    }

    public synchronized void charge(long tokensUsed, double costUsd) {
        if (tokensUsed < 0) throw new IllegalArgumentException("Token usage must not be negative");
        long micros = microUsd(costUsd);
        long nextTokens = Math.addExact(tokens.get(), tokensUsed);
        long nextCost = Math.addExact(costMicroUsd.get(), micros);
        tokens.set(nextTokens);
        costMicroUsd.set(nextCost);
    }

    public boolean costLimited() { return maxCostUsd > 0 || durableCostLimit >= 0; }

    public boolean usageLimited() { return maxTokens > 0 || costLimited(); }

    boolean durable() { return durable != null; }

    public synchronized Reservation reserve(long estimatedTokens, double estimatedCostUsd) {
        return reserve(estimatedTokens, estimatedCostUsd, true);
    }

    synchronized Reservation reserve(long estimatedTokens, double estimatedCostUsd, boolean costPriced) {
        if (estimatedTokens < 1) throw new IllegalArgumentException("Token estimate must be positive");
        long micros = microUsd(estimatedCostUsd);
        if (exhausted() != null || maxTokens > 0 && estimatedTokens > maxTokens - tokens.get() - reservedTokens
                || maxCostUsd > 0 && micros > (long) Math.floor(maxCostUsd * 1_000_000) - costMicroUsd.get() - reservedMicroUsd) {
            throw new LlmException(LlmException.Kind.BUDGET, "Case budget cannot admit the estimated provider call");
        }
        long nextTokens = Math.addExact(reservedTokens, estimatedTokens);
        long nextCost = Math.addExact(reservedMicroUsd, micros);
        CaseSpendLedger.Attempt attempt;
        try { attempt = durable == null ? null : durable.reserve(estimatedTokens, micros, costPriced); }
        catch (org.springframework.dao.DataAccessException e) {
            throw new LlmException(LlmException.Kind.BUDGET, "Durable case spend admission unavailable", e);
        }
        reservedTokens = nextTokens;
        reservedMicroUsd = nextCost;
        return new Reservation(estimatedTokens, micros, attempt);
    }

    public final class Reservation implements AutoCloseable {
        private final long estimate;
        private final long micros;
        private boolean settled;
        private final CaseSpendLedger.Attempt attempt;
        private Reservation(long estimate, long micros, CaseSpendLedger.Attempt attempt) {
            this.estimate = estimate; this.micros = micros; this.attempt = attempt;
        }

        public void settle(long actualTokens, double actualCostUsd) {
            if (actualTokens < 0) throw new IllegalArgumentException("Token usage must not be negative");
            microUsd(actualCostUsd);
            synchronized (CaseBudget.this) {
                if (settled) throw new IllegalStateException("Reservation already settled");
                if (attempt != null) {
                    try { attempt.settle(actualTokens, microUsd(actualCostUsd)); }
                    catch (org.springframework.dao.DataAccessException e) {
                        throw new LlmException(LlmException.Kind.BUDGET, "Durable case spend settlement unavailable", e);
                    }
                }
                charge(actualTokens, actualCostUsd);
                reservedTokens -= estimate;
                reservedMicroUsd -= micros;
                settled = true;
            }
        }

        public void cancel() { settle(0, 0); }

        @Override public void close() {
            synchronized (CaseBudget.this) {
                if (!settled) {
                    long nextTokens = Math.addExact(tokens.get(), estimate);
                    long nextCost = Math.addExact(costMicroUsd.get(), micros);
                    reservedTokens -= estimate;
                    reservedMicroUsd -= micros;
                    tokens.set(nextTokens);
                    costMicroUsd.set(nextCost);
                    settled = true;
                }
            }
        }
    }

    private static long microUsd(double cost) {
        if (!Double.isFinite(cost) || cost < 0 || cost > Long.MAX_VALUE / 1_000_000.0) {
            throw new IllegalArgumentException("Invalid usage cost");
        }
        return (long) Math.ceil(cost * 1_000_000);
    }

    /** Returns the exhausted dimension, or null when the budget still has room. */
    public String exhausted() {
        if (maxTokens > 0 && tokens.get() >= maxTokens) {
            return "tokens " + tokens.get() + "/" + maxTokens;
        }
        if (maxCostUsd > 0 && costMicroUsd.get() / 1_000_000.0 >= maxCostUsd) {
            return "cost USD " + costMicroUsd.get() / 1_000_000.0 + "/" + maxCostUsd;
        }
        if (maxNanos != 0 && System.nanoTime() - startedNanos >= maxNanos) {
            return "wall-clock";
        }
        return null;
    }

    /** Milliseconds left before the wall-clock deadline; Long.MAX_VALUE when unbounded. */
    public long remainingMillis() {
        return maxNanos == 0 ? Long.MAX_VALUE
                : Math.max(0, (maxNanos - (System.nanoTime() - startedNanos)) / 1_000_000);
    }

    public long tokensUsed() {
        return tokens.get();
    }

    public double costUsd() {
        return costMicroUsd.get() / 1_000_000.0;
    }
}
