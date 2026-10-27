package io.github.chdurai.agenticbanking.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Map;

/**
 * {@code platform.llm.*}: routing, resilience, budgets and approved providers.
 * Routes fall back to the legacy {@code agents.models} / {@code agents.fallback-model} keys so
 * existing configuration keeps working while teams migrate.
 */
@ConfigurationProperties("platform.llm")
public record LlmProperties(
        /** Logical provider name used in breaker/metric keys and the approved-provider check. */
        @DefaultValue("default") String provider,
        /** Explicit acknowledgement that a route may use the same model as primary and fallback. */
        @DefaultValue("false") boolean allowFallbackEqualsPrimary,
        Map<String, Route> routes,
        @DefaultValue Resilience resilience,
        @DefaultValue Providers providers,
        @DefaultValue Budget budget) {

    public LlmProperties {
        routes = routes == null ? Map.of() : Map.copyOf(routes);
        resilience = resilience == null ? Resilience.defaults() : resilience;
        providers = providers == null ? new Providers(List.of()) : providers;
        budget = budget == null ? new Budget(Map.of()) : budget;
    }

    public static LlmProperties defaults() {
        return new LlmProperties("default", false, Map.of(), Resilience.defaults(),
                new Providers(List.of()), new Budget(Map.of()));
    }

    public LlmProperties withResilience(Resilience r) {
        return new LlmProperties(provider, allowFallbackEqualsPrimary, routes, r, providers, budget);
    }

    public LlmProperties withRoutes(Map<String, Route> r) {
        return new LlmProperties(provider, allowFallbackEqualsPrimary, r, resilience, providers, budget);
    }

    public LlmProperties withAllowSameFallback(boolean allow) {
        return new LlmProperties(provider, allow, routes, resilience, providers, budget);
    }

    public LlmProperties withProviders(Providers p) {
        return new LlmProperties(provider, allowFallbackEqualsPrimary, routes, resilience, p, budget);
    }

    public LlmProperties withBudget(Budget b) {
        return new LlmProperties(provider, allowFallbackEqualsPrimary, routes, resilience, providers, b);
    }

    /** All fields optional: unset values inherit from the legacy agent keys or caller defaults. */
    public record Route(String primary, String fallback, Double temperature, Integer maxTokens,
                        String promptVersion) {
    }

    public record Resilience(
            @DefaultValue("60000") long timeoutMs,
            @DefaultValue Retry retry,
            @DefaultValue Breaker breaker,
            @DefaultValue Bulkhead bulkhead,
            @DefaultValue Quota quota,
            @DefaultValue("false") boolean killSwitch) {

        public static Resilience defaults() {
            return new Resilience(60_000, new Retry(2, 500, 2.0, 0.3),
                    new Breaker(50, 20, 10, 30_000, 3), new Bulkhead(16, 2_000), new Quota(0), false);
        }

        public Resilience withKillSwitch(boolean on) {
            return new Resilience(timeoutMs, retry, breaker, bulkhead, quota, on);
        }

        public Resilience withTimeoutMs(long ms) {
            return new Resilience(ms, retry, breaker, bulkhead, quota, killSwitch);
        }

        public Resilience withRetry(Retry r) {
            return new Resilience(timeoutMs, r, breaker, bulkhead, quota, killSwitch);
        }

        public Resilience withBreaker(Breaker b) {
            return new Resilience(timeoutMs, retry, b, bulkhead, quota, killSwitch);
        }

        public Resilience withBulkhead(Bulkhead b) {
            return new Resilience(timeoutMs, retry, breaker, b, quota, killSwitch);
        }

        public Resilience withQuota(Quota q) {
            return new Resilience(timeoutMs, retry, breaker, bulkhead, q, killSwitch);
        }
    }

    /** Retries apply only to 429 / 5xx / transport failures, with jittered exponential backoff. */
    public record Retry(@DefaultValue("2") int max, @DefaultValue("500") long initialBackoffMs,
                        @DefaultValue("2.0") double multiplier, @DefaultValue("0.3") double jitter) {
    }

    public record Breaker(@DefaultValue("50") float failureRatePercent,
                          @DefaultValue("20") int slidingWindowSize,
                          @DefaultValue("10") int minimumCalls,
                          @DefaultValue("30000") long waitOpenMs,
                          @DefaultValue("3") int halfOpenCalls) {
    }

    public record Bulkhead(@DefaultValue("16") int maxConcurrent, @DefaultValue("2000") long maxWaitMs) {
    }

    public record Quota(@DefaultValue("0") long tpm) {
        public Quota {
            if (tpm < 0) throw new IllegalArgumentException("Token quota must not be negative");
        }
    }

    /** Empty approved list means "no restriction" (dev); non-empty is enforced at startup. */
    public record Providers(List<String> approved) {
        public Providers {
            approved = approved == null ? List.of() : List.copyOf(approved);
        }
    }

    /** Per-case budgets by flow; zero means unlimited for that dimension. */
    public record Budget(Map<String, FlowBudget> perCase, Map<String, Map<String, FlowBudget>> tenants) {
        public Budget(Map<String, FlowBudget> perCase) { this(perCase, Map.of()); }
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Budget {
            perCase = perCase == null ? Map.of() : Map.copyOf(perCase);
            tenants = tenants == null ? Map.of() : tenants.entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Map.copyOf(e.getValue())));
        }

        public CaseBudget newCaseBudget(String flow) {
            FlowBudget b = tenants.getOrDefault(io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId(), Map.of())
                    .getOrDefault(flow, perCase.get(flow));
            return b == null ? CaseBudget.unlimited() : new CaseBudget(b.maxTokens(), b.maxCostUsd(), b.maxMs());
        }

        public io.github.chdurai.agenticbanking.security.CallerContext.Scope openForFlow(String flow) {
            var caller = io.github.chdurai.agenticbanking.security.CallerContext.current();
            return (caller.budget() == null ? caller.withBudget(newCaseBudget(flow)) : caller).open();
        }
    }

    public record FlowBudget(long maxTokens, double maxCostUsd, long maxMs) {
        public FlowBudget {
            CaseBudget.validateLimits(maxTokens, maxCostUsd, maxMs);
        }
    }
}
