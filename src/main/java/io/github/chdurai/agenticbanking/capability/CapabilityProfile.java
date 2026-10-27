package io.github.chdurai.agenticbanking.capability;

import java.util.List;

/**
 * Declarative, versioned least-privilege profile for one agent (WI-20, design 7.7).
 * Loaded from {@code platform.capabilities.profiles.<agent>} and validated at startup
 * against {@link CapabilityCatalog}; enforced at runtime by {@link CapabilityGuard}.
 */
public record CapabilityProfile(
        String agent,
        List<String> dataViews,
        List<String> tools,
        List<String> retrieval,
        int maxTokensIn,
        int maxTokensOut,
        int maxToolCalls,
        long timeoutMs,
        String outputSchema,
        boolean canRaiseStrictnessOnly) {
}
