package io.github.chdurai.agenticbanking.ops;

/** Correlates HTTP exchanges, including tool-loop calls, on the model worker thread. */
public record ProviderExchangeContext(String flow, String caseId, String agent) {
    private static final ThreadLocal<ProviderExchangeContext> CURRENT = new ThreadLocal<>();

    public static ProviderExchangeContext current() {
        return CURRENT.get();
    }

    public static void set(ProviderExchangeContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
