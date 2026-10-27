package io.github.chdurai.agenticbanking.llm;

/**
 * Estimated tokens-per-minute admission control. Memory demos use a local sliding window;
 * JDBC deployments share an atomic provider-wide fixed-minute reservation window.
 */
public interface QuotaLimiter {

    /** Reserve for one physical provider attempt; false means the quota is exhausted. */
    boolean tryAcquire(long estimatedTokens);
}
