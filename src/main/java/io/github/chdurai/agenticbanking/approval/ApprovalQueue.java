package io.github.chdurai.agenticbanking.approval;

import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Human-in-the-loop approval service. Agents never execute consequential actions unreviewed.
 *
 * <ul>
 *   <li>Decisions are commands ({@link ApprovalCommand}) by an identified {@link ReviewerIdentity}.</li>
 *   <li>Role match per flow and four-eyes (requester cannot decide their own request).</li>
 *   <li>The decision is recorded atomically (optimistic CAS) <em>before</em> the approved action runs; the
 *       action runs outside any lock and its result is a separate fact (APPROVED vs EXECUTION_FAILED).</li>
 *   <li>Requests expire: first escalated at half the TTL, then closed as EXPIRED with the safe default
 *       (nothing executed).</li>
 *   <li>Persisted through {@link ApprovalStore}; no closures are held, so pending requests survive restarts.</li>
 * </ul>
 */
@Component
public class ApprovalQueue {

    private static final Logger log = LoggerFactory.getLogger(ApprovalQueue.class);
    private static final int MAX_LIST = 500;

    private final ApprovalStore store;
    private final AgentMetrics metrics;
    private final PlatformEventPublisher events; // null in plain unit tests
    private final Map<String, ApprovalActionHandler> handlers;
    private final List<ApprovalOutcomeListener> listeners;
    private final HitlProperties props;

    public ApprovalQueue(AgentMetrics metrics) {
        this(metrics, null, new InMemoryApprovalStore(), List.of(), List.of(), new HitlProperties());
    }

    @Autowired
    public ApprovalQueue(AgentMetrics metrics, PlatformEventPublisher events, ApprovalStore store,
                         List<ApprovalActionHandler> handlers, List<ApprovalOutcomeListener> listeners,
                         HitlProperties props) {
        this.metrics = metrics;
        this.events = events;
        this.store = store;
        this.handlers = handlers.stream().collect(Collectors.toMap(ApprovalActionHandler::type, h -> h));
        this.listeners = listeners;
        this.props = props;
    }

    public ApprovalRequest submit(String flow, String subjectId, String title, List<String> details) {
        return submit(flow, subjectId, title, details, null, null, null, null);
    }

    public ApprovalRequest submit(String flow, String subjectId, String title, List<String> details,
                                  ActionSpec action) {
        return submit(flow, subjectId, title, details, null, action, null, null);
    }

    public ApprovalRequest submit(String flow, String subjectId, String title, List<String> details,
                                  ApprovalEvidencePacket packet) {
        return submit(flow, subjectId, title, details, packet, null, null, null);
    }

    /** Backward-compatible overload (without explicit evidence packet). */
    public ApprovalRequest submit(String flow, String subjectId, String title, List<String> details,
                                  ActionSpec action, String requestedBy, String requiredRole) {
        return submit(flow, subjectId, title, details, null, action, requestedBy, requiredRole);
    }

    /**
     * @param packet       evidence packet for reviewer context; null falls back to a legacy packet from title/details
     * @param action       what to run on approval (resolved by a handler), or null
     * @param requestedBy  who/what raised it; that identity may not approve it (four-eyes)
     * @param requiredRole role needed to decide; defaults from the flow
     */
    public ApprovalRequest submit(String flow, String subjectId, String title, List<String> details,
                                  ApprovalEvidencePacket packet,
                                  ActionSpec action, String requestedBy, String requiredRole) {
        Instant now = Instant.now();
        ApprovalEvidencePacket evidence = packet == null
                ? ApprovalEvidencePacket.legacy(flow, subjectId, title, details)
                : packet;
        boolean blindFirstRequired = props.sampleBlindFirst(flow, subjectId);
        boolean reasonCodeRequired = evidence.nonTrivialRisk()
                || evidence.maxRiskScore() >= props.getNonTrivialRiskMinScore();
        ApprovalRequest request = new ApprovalRequest(UUID.randomUUID().toString(), flow, subjectId, title,
                details == null ? List.of() : List.copyOf(details), evidence, ApprovalRequest.Status.PENDING, now, null,
                null, null, null, List.of(), requestedBy,
                requiredRole == null ? Roles.forFlow(flow) : requiredRole,
                now.plus(Duration.ofHours(props.ttlHoursFor(flow))), null,
                blindFirstRequired, null, reasonCodeRequired, action, 0);
        store.insert(request);
        metrics.recordCounter("hitl.submitted", "flow", flow);
        if (blindFirstRequired) {
            metrics.recordCounter("hitl.blind_first.sampled", "flow", flow);
        }
        log.info("[hitl] submitted {} for {} {}: {}", request.id(), flow, subjectId, title);
        return request;
    }

    /** Dev/back-compat path: identity is the supplied name; roles are checked only when enforcement is on. */
    public ApprovalRequest decide(String id, boolean approve, String decidedBy, String note) {
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy is required");
        }
        ApprovalCommand cmd = approve
                ? new ApprovalCommand.Approve("LEGACY_DECISION", note)
                : new ApprovalCommand.Reject("LEGACY_DECISION", note);
        ApprovalRequest current = require(id);
        return handle(id, cmd, new ReviewerIdentity(decidedBy, java.util.Set.of(current.requiredRole())));
    }

    public ApprovalRequest handle(String id, ApprovalCommand command, ReviewerIdentity reviewer) {
        return handleVersioned(id, -1, command, reviewer);
    }

    public ApprovalRequest handleVersioned(String id, long expectedVersion, ApprovalCommand command, ReviewerIdentity reviewer) {
        if (io.github.chdurai.agenticbanking.security.CallerContext.isBound()
                && !io.github.chdurai.agenticbanking.security.CallerContext.current().equals(io.github.chdurai.agenticbanking.security.CallerContext.DEV)) {
            var caller = io.github.chdurai.agenticbanking.security.CallerContext.current();
            reviewer = new ReviewerIdentity(caller.actor(), caller.roles());
        }
        if (reviewer == null || reviewer.id() == null || reviewer.id().isBlank()) {
            throw new IllegalArgumentException("decidedBy is required");
        }
        ApprovalRequest current = require(id);
        if (expectedVersion >= 0 && current.version() != expectedVersion) {
            throw new io.github.chdurai.agenticbanking.cases.CaseStoreExceptions.StaleVersionException("Approval changed concurrently");
        }
        if (!current.isOpen()) {
            throw new IllegalStateException("Request " + id + " already " + current.status());
        }
        if (Instant.now().isAfter(current.expiresAt())) {
            throw new IllegalStateException("Request " + id + " has expired");
        }
        if ((props.isEnforceRoles() || !io.github.chdurai.agenticbanking.security.CallerContext.current().equals(io.github.chdurai.agenticbanking.security.CallerContext.DEV))
                && !reviewer.has(current.requiredRole()) && !reviewer.has(Roles.PLATFORM_ADMIN)) {
            throw new ApprovalDeniedException("Role " + current.requiredRole() + " is required for " + current.flow());
        }
        if (current.requestedBy() != null && current.requestedBy().equals(reviewer.id())) {
            throw new ApprovalDeniedException("Four-eyes: the requester cannot decide their own request");
        }
        if (command instanceof ApprovalCommand.OverrideDecision o && (o.reason() == null || o.reason().isBlank())) {
            throw new IllegalArgumentException("An override requires a reason");
        }
        if (command instanceof ApprovalCommand.OverrideDecision o && isBlank(o.reasonCode())) {
            throw new IllegalArgumentException("An override requires a reasonCode");
        }
        if ((command instanceof ApprovalCommand.Approve)
                && current.reasonCodeRequired()
                && isBlank(command.reasonCode())) {
            throw new IllegalArgumentException("A reasonCode is required for this approval");
        }
        if (current.blindFirstRequired() && current.blindFirstAssessment() == null) {
            throw new IllegalStateException("Blind-first assessment is required before a final decision");
        }

        ApprovalRequest.Status status = switch (command) {
            case ApprovalCommand.Approve a -> ApprovalRequest.Status.APPROVED;
            case ApprovalCommand.Reject r -> ApprovalRequest.Status.REJECTED;
            case ApprovalCommand.OverrideDecision o -> ApprovalRequest.Status.OVERRIDDEN;
        };
        ApprovalRequest decided = current.decided(status, reviewer.id(), command.reasonCode(), command.note(), Instant.now());
        if (!store.replace(current, decided)) {
            if (expectedVersion >= 0) throw new io.github.chdurai.agenticbanking.cases.CaseStoreExceptions.StaleVersionException("Approval changed concurrently");
            throw new IllegalStateException("Request " + id + " was changed concurrently; reload and retry");
        }
        metrics.recordCounter("hitl.decisions", "outcome", status.name() + "." + current.flow());

        ApprovalRequest result = decided;
        if (status == ApprovalRequest.Status.APPROVED && current.action() != null) {
            result = executeAction(decided);
        }
        publish("approval.decided", result, result.status() + " by " + reviewer.id());
        notifyListeners(result);
        log.info("[hitl] {} {} by {}", id, result.status(), reviewer.id());
        return result;
    }

    public ApprovalRequest captureBlindFirst(String id, BlindFirstAssessment.Decision decision,
                                             String reasonCode, String note, ReviewerIdentity reviewer) {
        if (io.github.chdurai.agenticbanking.security.CallerContext.isBound()
                && !io.github.chdurai.agenticbanking.security.CallerContext.current().equals(io.github.chdurai.agenticbanking.security.CallerContext.DEV)) {
            var caller = io.github.chdurai.agenticbanking.security.CallerContext.current();
            reviewer = new ReviewerIdentity(caller.actor(), caller.roles());
        }
        if (reviewer == null || reviewer.id() == null || reviewer.id().isBlank()) {
            throw new IllegalArgumentException("decidedBy is required");
        }
        ApprovalRequest current = require(id);
        if (!current.isOpen()) {
            throw new IllegalStateException("Request " + id + " already " + current.status());
        }
        if (!current.blindFirstRequired()) {
            throw new IllegalStateException("Blind-first is not required for request " + id);
        }
        if (current.blindFirstAssessment() != null) {
            throw new IllegalStateException("Blind-first assessment already captured for request " + id);
        }
        if ((props.isEnforceRoles() || !io.github.chdurai.agenticbanking.security.CallerContext.current().equals(io.github.chdurai.agenticbanking.security.CallerContext.DEV))
                && !reviewer.has(current.requiredRole()) && !reviewer.has(Roles.PLATFORM_ADMIN)) {
            throw new ApprovalDeniedException("Role " + current.requiredRole() + " is required for " + current.flow());
        }
        if (current.requestedBy() != null && current.requestedBy().equals(reviewer.id())) {
            throw new ApprovalDeniedException("Four-eyes: the requester cannot decide their own request");
        }
        if (isBlank(reasonCode)) {
            throw new IllegalArgumentException("Blind-first requires a reasonCode");
        }
        BlindFirstAssessment assessment = new BlindFirstAssessment(reviewer.id(), decision, reasonCode, note, Instant.now());
        ApprovalRequest updated = current.withBlindFirst(assessment);
        if (!store.replace(current, updated)) {
            throw new IllegalStateException("Request " + id + " was changed concurrently; reload and retry");
        }
        metrics.recordCounter("hitl.blind_first.captured", "flow", current.flow());
        publish("approval.blind_first.captured", updated, "Blind-first assessment captured by " + reviewer.id());
        return updated;
    }

    /** Explicit recovery only for adapters that promise stable-key reconciliation, never arbitrary handlers. */
    public ApprovalRequest reconcile(String id) {
        var caller = io.github.chdurai.agenticbanking.security.CallerContext.current();
        return reconcile(id, new ReviewerIdentity(caller.actor(), caller.roles()));
    }

    public ApprovalRequest reconcile(String id, ReviewerIdentity reviewer) {
        if (io.github.chdurai.agenticbanking.security.CallerContext.isBound()
                && !io.github.chdurai.agenticbanking.security.CallerContext.current().equals(io.github.chdurai.agenticbanking.security.CallerContext.DEV)) {
            var caller = io.github.chdurai.agenticbanking.security.CallerContext.current();
            reviewer = new ReviewerIdentity(caller.actor(), caller.roles());
        }
        ApprovalRequest current = require(id);
        if (reviewer == null || reviewer.id() == null || reviewer.id().isBlank()) {
            throw new IllegalArgumentException("Authenticated reviewer required for reconciliation");
        }
        if (!reviewer.has(current.requiredRole()) && !reviewer.has(Roles.PLATFORM_ADMIN)) {
            throw new ApprovalDeniedException("Flow role required for reconciliation");
        }
        if (current.status() != ApprovalRequest.Status.APPROVED || current.action() == null
                || !(handlers.get(current.action().type()) instanceof RecoverableApprovalActionHandler)) {
            throw new IllegalStateException("Approval has no recoverable approved action");
        }
        if (!current.executionLog().isEmpty()) return current;
        return executeAction(current);
    }

    private ApprovalRequest executeAction(ApprovalRequest decided) {
        List<String> executionLog;
        ApprovalRequest.Status status = ApprovalRequest.Status.APPROVED;
        ApprovalActionHandler handler = handlers.get(decided.action().type());
        try {
            if (handler == null) {
                throw new IllegalStateException("no handler for action " + decided.action().type());
            }
            executionLog = handler.execute(decided);
        } catch (RuntimeException e) {
            executionLog = List.of("Execution failed: " + e.getMessage());
            status = ApprovalRequest.Status.EXECUTION_FAILED;
            metrics.recordCounter("hitl.execution_failed", "flow", decided.flow());
        }
        ApprovalRequest withResult = decided.withExecution(status, executionLog);
        if (!store.replace(decided, withResult)) {
            log.error("[hitl] {} execution result could not be recorded (concurrent update)", decided.id());
            ApprovalRequest latest = require(decided.id());
            if (!latest.executionLog().isEmpty()) return latest;
            throw new IllegalStateException("Execution result not recorded; reconciliation required");
        }
        return withResult;
    }

    /** Escalate at half the TTL, then close as EXPIRED with the safe default (nothing executed). */
    public void sweep() {
        sweep(Instant.now());
    }

    public int sweep(Instant now) {
        int changed = 0;
        for (ApprovalRequest r : store.list(ApprovalRequest.Status.PENDING, 10_000)) {
            if (!now.isBefore(r.expiresAt())) {
                ApprovalRequest expired = r.decided(ApprovalRequest.Status.EXPIRED, "system:expiry",
                        "EXPIRED_TIMEOUT",
                        "Expired without a decision; safe default applied (no action executed)", now);
                if (store.replace(r, expired)) {
                    metrics.recordCounter("hitl.expired", "flow", r.flow());
                    publish("approval.expired", expired, "EXPIRED: safe default, no action taken");
                    notifyListeners(expired);
                    changed++;
                }
            } else if (r.escalatedAt() == null && !now.isBefore(escalationPoint(r))) {
                if (store.replace(r, r.escalated(now))) {
                    metrics.recordCounter("hitl.escalated", "flow", r.flow());
                    publish("approval.escalated", r, "Escalated: no decision by half of the SLA window");
                    changed++;
                }
            }
        }
        return changed;
    }

    private static Instant escalationPoint(ApprovalRequest r) {
        return r.createdAt().plus(Duration.between(r.createdAt(), r.expiresAt()).dividedBy(2));
    }

    private void publish(String type, ApprovalRequest r, String detail) {
        if (events != null) {
            events.publish(PlatformEvent.of(type, r.flow(), r.subjectId(), detail));
        }
    }

    private void notifyListeners(ApprovalRequest r) {
        for (ApprovalOutcomeListener l : listeners) {
            try {
                l.onOutcome(r);
            } catch (RuntimeException e) {
                log.error("[hitl] outcome listener {} failed for {}: {}", l.getClass().getSimpleName(), r.id(), e.getMessage());
            }
        }
    }

    private ApprovalRequest require(String id) {
        ApprovalRequest r = store.find(id);
        if (r == null) {
            throw new IllegalArgumentException("Unknown approval request: " + id);
        }
        return r;
    }

    public ApprovalRequest get(String id) {
        ApprovalRequest r = store.find(id);
        return r == null || !readable(r) ? null : r;
    }

    /** Newest first, capped; status null = all. */
    public List<ApprovalRequest> list(ApprovalRequest.Status status) {
        return store.list(status, MAX_LIST).stream().filter(ApprovalQueue::readable).toList();
    }

    private static boolean readable(ApprovalRequest r) {
        var roles = io.github.chdurai.agenticbanking.security.CallerContext.current().roles();
        return roles.contains(Roles.PLATFORM_ADMIN) || roles.contains(r.requiredRole());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
