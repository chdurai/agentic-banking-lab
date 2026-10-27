package io.github.chdurai.agenticbanking.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.chdurai.agenticbanking.capability.AgentCapabilityContext;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import io.github.chdurai.agenticbanking.tools.port.ToolAuditEnvelope;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Emits deterministic tool-call audit envelopes (design 7.5):
 * {caseId, agentId, tool, argsHash, resultHash, latency, outcome}.
 */
@Component
public class ToolCallAuditor {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final PlatformEventPublisher events;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.chdurai.agenticbanking.investigation.InvestigationStore lineage;

    public ToolCallAuditor(PlatformEventPublisher events) {
        this.events = events;
    }

    public <A, R> R audited(String toolId, A args, Supplier<R> call) {
        long start = System.nanoTime();
        String argsHash = hash(args);
        String resultHash = "";
        String outcome = "OK";
        AgentCapabilityContext agent = AgentCapabilityContext.current().orElse(null);
        String observation = lineage == null ? null : lineage.sourceIntent("TOOL", agent == null ? "platform" : agent.agentId(),
                toolId, "synthetic-adapters-v1", args);
        try {
            R result = call.get();
            resultHash = hash(result);
            if (lineage != null) lineage.sourceResult(observation, result, "SUCCEEDED");
            return result;
        } catch (RuntimeException e) {
            outcome = "ERROR:" + e.getClass().getSimpleName();
            if (lineage != null && observation != null) {
                try { lineage.sourceResult(observation, java.util.Map.of("error", e.getClass().getSimpleName()), "FAILED"); }
                catch (RuntimeException failure) { e.addSuppressed(failure); }
            }
            throw e;
        } finally {
            AgentCapabilityContext ctx = AgentCapabilityContext.current().orElse(null);
            String agentId = ctx == null ? "platform" : ctx.agentId();
            String caseId = ctx == null || ctx.caseId() == null || ctx.caseId().isBlank()
                    ? "unknown" : ctx.caseId();
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            ToolAuditEnvelope envelope = new ToolAuditEnvelope(
                    caseId, agentId, toolId, argsHash, resultHash, latencyMs, outcome);
            events.publish(PlatformEvent.of("tool.call", "tool", caseId, asJson(envelope)));
        }
    }

    private static String asJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"tool-audit-serialization\"}";
        }
    }

    private static String hash(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(asJson(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
