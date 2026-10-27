package io.github.chdurai.agenticbanking.llm;

/**
 * Failure of a gateway call. {@link Kind} lets callers and the resilience layer distinguish
 * provider outages (counted by the breaker) from policy refusals and bad model output.
 */
public class LlmException extends RuntimeException {

    public enum Kind {
        /** Operator switched all LLM use off; flows must take deterministic / human paths. */
        KILL_SWITCH,
        /** Per-case token, cost or wall-clock budget exhausted. */
        BUDGET,
        /** Local/shared estimated-token quota is exhausted or its admission store is unavailable. */
        QUOTA,
        /** Too many concurrent calls to the provider (bulkhead full). */
        BULKHEAD_FULL,
        /** Breaker for the route is open; call not attempted. */
        BREAKER_OPEN,
        /** Provider health admission refused; shared mode coordinates all replicas using the same account. */
        PROVIDER_BUDGET,
        /** Call exceeded the per-call timeout. */
        TIMEOUT,
        /** Provider answered with an empty body. */
        EMPTY_RESPONSE,
        /** Transport error or provider-side HTTP failure. */
        PROVIDER,
        /** Model replied but not with schema-valid output, even after one repair attempt. */
        OUTPUT_INVALID
    }

    private final Kind kind;

    public LlmException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** True for failures that mean "the LLM path is unavailable"; flows degrade to humans. */
    public boolean unavailable() {
        return kind != Kind.OUTPUT_INVALID;
    }
}
