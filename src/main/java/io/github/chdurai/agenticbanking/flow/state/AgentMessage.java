package io.github.chdurai.agenticbanking.flow.state;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Envelope for agent-to-agent traffic. {@code seq} gives a total order per case. */
public record AgentMessage(
        String messageId,
        String caseId,
        String traceId,
        long seq,
        String from,
        String to,
        Type type,
        List<String> refs,
        Map<String, Object> payload,
        Instant createdAt,
        int schemaVersion) {

    public enum Type { FINDING_PUBLISHED, CHALLENGE, CHALLENGE_RESPONSE, INFO_REQUEST, INFO_RESPONSE, ESCALATE, ABSTAIN }

    public AgentMessage {
        refs = refs == null ? List.of() : List.copyOf(refs);
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
