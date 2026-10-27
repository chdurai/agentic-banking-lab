package io.github.chdurai.agenticbanking.cases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Idempotent execution of case-creating requests: same key + same body returns the stored result with
 * no second LLM call; same key + different body is a conflict. Outbox failure never changes a result.
 */
@Service
public class CaseService {

    private final CaseStore store;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.chdurai.agenticbanking.llm.LlmProperties llm;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.chdurai.agenticbanking.ops.CaseSloMetrics metrics;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private io.github.chdurai.agenticbanking.llm.CaseSpendingScopes spending;

    public CaseService(CaseStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public CaseStore store() {
        return store;
    }

    public record Outcome<T>(String caseId, T result, boolean replayed) {
    }

    public <T> Outcome<T> runIdempotent(String flow, String idempotencyKey, String subject, Object request,
                                        Class<T> resultType, String eventType, Supplier<T> work) {
        String hash = sha256(json(request));
        CaseStore.Claim claim = store.claim(flow, idempotencyKey, hash, pseudonym(subject));
        CaseRecord rec = claim.record();
        if (!claim.created()) {
            if (rec.resultJson() != null) {
                return new Outcome<>(rec.caseId(), read(rec.resultJson(), resultType), true);
            }
            if (rec.status() != CaseStatus.FAILED) {
                throw new CaseStoreExceptions.InProgressException("Request with this Idempotency-Key is still running");
            }
            // A failed attempt produced no result: retry it under the same case (optimistic version guards races).
            rec = store.transition(rec.caseId(), rec.version(), CaseStatus.OPEN, null, "system", "case.retried", null);
        }
        T result;
        try (var execution = CaseExecution.open(rec.caseId());
             var budget = spending == null
                     ? (llm == null ? io.github.chdurai.agenticbanking.llm.LlmProperties.defaults() : llm).budget().openForFlow(flow)
                     : spending.open(flow, rec.caseId())) {
            result = work.get();
        } catch (RuntimeException e) {
            var failed = store.transition(rec.caseId(), rec.version(), CaseStatus.FAILED, null, "system", "case.failed", null);
            if (metrics != null) metrics.processed(failed);
            throw e;
        }
        String resultJson = json(result);
        var body = mapper.valueToTree(result);
        String state = body.path("status").asText(body.path("decision").asText());
        CaseStatus status = state.equals("BLOCKED") ? CaseStatus.BLOCKED
                : body.hasNonNull("approvalId") || state.startsWith("PENDING_") || state.equals("MANUAL_REVIEW")
                || state.equals("COMPLIANCE_HOLD") || state.equals("CONFIDENTIAL_ESCALATION") || state.equals("NEEDS_REVIEW")
                ? CaseStatus.PENDING_APPROVAL : CaseStatus.COMPLETED;
        var completed = store.transition(rec.caseId(), rec.version(), status, resultJson, "system", eventType,
                new OutboxMessage(0, rec.caseId(), eventType, flow, rec.subjectRef(), resultJson, 0, null));
        if (metrics != null) metrics.processed(completed);
        return new Outcome<>(rec.caseId(), result, false);
    }

    public static String pseudonym(String subject) {
        return "sub_" + sha256(subject == null ? "" : subject).substring(0, 16);
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String s, Class<T> type) {
        try {
            return mapper.readValue(s, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored result unreadable", e);
        }
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
