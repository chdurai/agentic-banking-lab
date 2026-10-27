package io.github.chdurai.agenticbanking.cases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import io.github.chdurai.agenticbanking.approval.Roles;
import io.github.chdurai.agenticbanking.security.CallerContext;
import io.github.chdurai.agenticbanking.security.CallerProperties;
import io.github.chdurai.agenticbanking.security.TenantMaintenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

@Service
public class AsyncCaseService {
    private static final Logger log = LoggerFactory.getLogger(AsyncCaseService.class);
    private final CaseStore store;
    private final ObjectMapper json;
    private final FlowCaseDispatcher dispatcher;
    private final CallerProperties callers;
    private final io.github.chdurai.agenticbanking.approval.ApprovalStore approvals;
    private final io.github.chdurai.agenticbanking.llm.LlmProperties llm;
    private final io.github.chdurai.agenticbanking.ops.CaseSloMetrics metrics;
    private final long maxRunMs;
    private final boolean workerEnabled;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore slots;
    private final Map<String, Future<?>> active = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    private io.github.chdurai.agenticbanking.llm.CaseSpendingScopes spending;

    public AsyncCaseService(CaseStore store, ObjectMapper json, FlowCaseDispatcher dispatcher, CallerProperties callers,
                            io.github.chdurai.agenticbanking.approval.ApprovalStore approvals,
                            io.github.chdurai.agenticbanking.llm.LlmProperties llm,
                            io.github.chdurai.agenticbanking.ops.CaseSloMetrics metrics,
                            @Value("${platform.cases.max-inflight:8}") int concurrency,
                            @Value("${platform.cases.max-run-ms:180000}") long maxRunMs,
                            @Value("${platform.cases.worker-enabled:true}") boolean workerEnabled) {
        if (concurrency < 1 || maxRunMs < 1000) throw new IllegalArgumentException("Invalid case worker limits");
        this.store = store;
        this.json = json;
        this.dispatcher = dispatcher;
        this.callers = callers;
        this.approvals = approvals;
        this.llm = llm;
        this.spending = new io.github.chdurai.agenticbanking.llm.CaseSpendingScopes(llm, java.util.Optional.empty());
        this.metrics = metrics;
        this.maxRunMs = maxRunMs;
        this.workerEnabled = workerEnabled;
        this.slots = new Semaphore(concurrency);
    }

    public CaseStore.Claim submit(String flow, String key, JsonNode request) {
        requireFlow(flow);
        authorize(flow);
        if (key == null || key.isBlank() || key.length() > 200 || key.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key (1-200 characters) is required");
        }
        Object prepared = dispatcher.prepare(flow, request);
        String encoded = encode(prepared);
        String hash = CaseService.sha256(request.toString());
        String subject = switch (prepared) {
            case io.github.chdurai.agenticbanking.model.LoanApplication a -> a.applicantId();
            case io.github.chdurai.agenticbanking.incident.Incident i -> i.incidentId();
            case io.github.chdurai.agenticbanking.grievance.Grievance g -> g.grievanceId();
            case io.github.chdurai.agenticbanking.documents.DocumentRequest d -> d.documentId();
            default -> throw new IllegalStateException("Unsupported flow");
        };
        return store.enqueue(flow, key, hash, CaseService.pseudonym(subject), encoded);
    }

    public CaseRecord get(String id) {
        CaseRecord row = store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        authorize(row.flow());
        if (row.status() == CaseStatus.PENDING_APPROVAL) {
            var linked = approvals.forCase(id);
            if (!linked.isEmpty() && linked.stream().noneMatch(io.github.chdurai.agenticbanking.approval.ApprovalRequest::isOpen)) {
                CaseStatus status = linked.stream().anyMatch(a -> a.status() == io.github.chdurai.agenticbanking.approval.ApprovalRequest.Status.EXECUTION_FAILED)
                        ? CaseStatus.EXECUTION_FAILED
                        : linked.stream().anyMatch(a -> a.status() == io.github.chdurai.agenticbanking.approval.ApprovalRequest.Status.EXPIRED)
                        ? CaseStatus.EXPIRED
                        : linked.stream().anyMatch(a -> a.status() == io.github.chdurai.agenticbanking.approval.ApprovalRequest.Status.REJECTED)
                        ? CaseStatus.REJECTED : CaseStatus.APPROVED;
                try {
                    // This is a workflow state, not a fabricated re-analysis of the original model result.
                    row = store.transition(id, row.version(), status, null, "system:approval",
                            "case.approval-outcome", null);
                } catch (CaseStoreExceptions.StaleVersionException e) {
                    row = store.find(id).orElseThrow();
                }
            }
        }
        return row;
    }

    public void authorize(String flow) {
        var roles = CallerContext.current().roles();
        if (!roles.contains(Roles.PLATFORM_ADMIN) && !roles.contains(Roles.forFlow(flow))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Flow role required");
        }
    }

    public static void requireFlow(String flow) {
        if (!List.of("loan", "incident", "grievance", "document").contains(flow)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown flow");
        }
    }

    @Scheduled(fixedDelayString = "${platform.cases.poll-ms:1000}", initialDelayString = "${platform.cases.poll-ms:1000}")
    public void drain() {
        if (!workerEnabled) return;
        for (CallerContext caller : TenantMaintenance.scopes(callers)) {
            try (var ignored = caller.open()) {
                for (CaseRecord row : store.running(100)) {
                    if (row.updatedAt().plusMillis(maxRunMs).isBefore(Instant.now())) {
                        try {
                            var failed = store.transition(row.caseId(), row.version(), CaseStatus.FAILED, null, "system:worker",
                                    "case.execution-uncertain", null);
                            metrics.processed(failed);
                            Future<?> task = active.get(row.caseId());
                            if (task != null) task.cancel(true);
                            log.error("Case {} exceeded its execution lease; reconciliation required, no automatic replay", row.caseId());
                        } catch (CaseStoreExceptions.StaleVersionException e) {
                            log.debug("Case {} completed during lease sweep", row.caseId());
                        }
                    }
                }
                for (CaseRecord queued : store.queued(100)) {
                    if (!slots.tryAcquire()) break;
                    CaseRecord running;
                    try {
                        running = store.transition(queued.caseId(), queued.version(), CaseStatus.RUNNING,
                                null, "system:worker", "case.started", null);
                    } catch (CaseStoreExceptions.StaleVersionException e) {
                        slots.release();
                        continue;
                    } catch (RuntimeException e) {
                        slots.release();
                        throw e;
                    }
                    var task = new java.util.concurrent.FutureTask<Void>(CallerContext.wrap(() -> execute(running)), null);
                    active.put(running.caseId(), task);
                    workers.execute(task);
                }
            }
        }
    }

    private void execute(CaseRecord running) {
        try (var execution = CaseExecution.open(running.caseId());
             var budget = spending.open(running.flow(), running.caseId())) {
            Object result = dispatcher.execute(running.flow(), running.requestJson());
            JsonNode body = json.valueToTree(result);
            String state = body.path("status").asText(body.path("decision").asText());
            boolean pending = body.hasNonNull("approvalId") || state.startsWith("PENDING_")
                    || state.equals("MANUAL_REVIEW") || state.equals("COMPLIANCE_HOLD")
                    || state.equals("CONFIDENTIAL_ESCALATION") || state.equals("NEEDS_REVIEW");
            CaseStatus status = state.equals("BLOCKED") ? CaseStatus.BLOCKED
                    : pending ? CaseStatus.PENDING_APPROVAL : CaseStatus.COMPLETED;
            String encoded = encode(result);
            var completed = store.transition(running.caseId(), running.version(), status, encoded, "system:worker", "case.processed",
                    new OutboxMessage(0, running.caseId(), "case.processed", running.flow(), running.subjectRef(),
                            encoded, 0, null));
            metrics.processed(completed);
        } catch (RuntimeException e) {
            log.error("Case {} processing failed", running.caseId(), e);
            try {
                var failed = store.transition(running.caseId(), running.version(), CaseStatus.FAILED, null, "system:worker", "case.failed", null);
                metrics.processed(failed);
            } catch (CaseStoreExceptions.StaleVersionException stale) {
                log.warn("Case {} already transitioned; failed work will not be replayed", running.caseId());
            } catch (RuntimeException storageFailure) {
                log.error("Case {} could not persist failure; execution lease requires reconciliation",
                        running.caseId(), storageFailure);
            }
        } finally {
            active.remove(running.caseId());
            slots.release();
        }
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize case data", e); }
    }

    @PreDestroy public void close() { workers.shutdownNow(); }
}
