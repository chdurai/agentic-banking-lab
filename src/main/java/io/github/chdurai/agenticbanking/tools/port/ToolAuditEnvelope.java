package io.github.chdurai.agenticbanking.tools.port;

/** Deterministic audit envelope for one tool port call. */
public record ToolAuditEnvelope(
        String caseId,
        String agentId,
        String tool,
        String argsHash,
        String resultHash,
        long latencyMs,
        String outcome) {
}
