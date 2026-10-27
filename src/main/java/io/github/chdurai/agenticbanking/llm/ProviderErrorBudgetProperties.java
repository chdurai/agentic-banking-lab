package io.github.chdurai.agenticbanking.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Provider-wide health admission; shared mode requires JDBC and is independent of the manual kill switch. */
@ConfigurationProperties("platform.llm.provider-error-budget")
public record ProviderErrorBudgetProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("50") float failureRatePercent,
        @DefaultValue("20") int slidingWindowSize,
        @DefaultValue("10") int minimumCalls,
        @DefaultValue("30000") long waitOpenMs,
        @DefaultValue("1") int halfOpenCalls,
        @DefaultValue("false") boolean shared) {
    public ProviderErrorBudgetProperties(boolean enabled, float failureRatePercent, int slidingWindowSize,
                                         int minimumCalls, long waitOpenMs, int halfOpenCalls) {
        this(enabled, failureRatePercent, slidingWindowSize, minimumCalls, waitOpenMs, halfOpenCalls, false);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ProviderErrorBudgetProperties {
        if (!Float.isFinite(failureRatePercent) || failureRatePercent <= 0 || failureRatePercent > 100
                || slidingWindowSize < 1 || minimumCalls < 1 || minimumCalls > slidingWindowSize
                || waitOpenMs < 1 || halfOpenCalls < 1 || halfOpenCalls > slidingWindowSize) {
            throw new IllegalArgumentException("Invalid provider error budget");
        }
        if (shared && !enabled) throw new IllegalArgumentException("Shared provider health requires the error budget to be enabled");
    }
}
