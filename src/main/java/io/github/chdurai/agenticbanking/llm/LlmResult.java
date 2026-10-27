package io.github.chdurai.agenticbanking.llm;

/**
 * A gateway answer plus the provenance every audit trail needs: which model really answered,
 * under which prompt version/hash, what it cost and whether the fallback route was used.
 */
public record LlmResult<T>(T value, String agentId, String model, String promptVersion,
                           String promptHash, long promptTokens, long completionTokens,
                           long latencyMs, double costUsd, boolean fallbackUsed, int providerCalls) {
}
