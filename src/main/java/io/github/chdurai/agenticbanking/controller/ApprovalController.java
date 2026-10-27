package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.approval.ApprovalQueue;
import io.github.chdurai.agenticbanking.approval.ApprovalCommand;
import io.github.chdurai.agenticbanking.approval.ApprovalDeniedException;
import io.github.chdurai.agenticbanking.approval.BlindFirstAssessment;
import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
import io.github.chdurai.agenticbanking.approval.HitlProperties;
import io.github.chdurai.agenticbanking.approval.ReviewerIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Human-in-the-loop review queue shared by the loan, incident and grievance flows. */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    public record Decision(String decidedBy, String reasonCode, String note) {}

    public record Override(String decidedBy, String reasonCode, String reason) {}

    public record BlindFirstDecision(String decidedBy, BlindFirstAssessment.Decision decision,
                                     String reasonCode, String note) {}

    private final ApprovalQueue queue;
    private final HitlProperties props;

    public ApprovalController(ApprovalQueue queue, HitlProperties props) {
        this.queue = queue;
        this.props = props;
    }

    @GetMapping
    public List<ApprovalRequest> list(@RequestParam(required = false) ApprovalRequest.Status status) {
        return queue.list(status).stream().map(this::redactForBlindFirst).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApprovalRequest> get(@PathVariable String id) {
        ApprovalRequest r = queue.get(id);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(redactForBlindFirst(r));
    }

    @PostMapping("/{id}/approve")
    public ApprovalRequest approve(@PathVariable String id, @RequestBody Decision d,
                                   @RequestHeader(value = "X-Reviewer-Id", required = false) String reviewer,
                                   @RequestHeader(value = "X-Reviewer-Roles", required = false) String roles) {
        return handle(id, new ApprovalCommand.Approve(d.reasonCode(), d.note()), identity(reviewer, roles, d.decidedBy()));
    }

    @PostMapping("/{id}/reject")
    public ApprovalRequest reject(@PathVariable String id, @RequestBody Decision d,
                                  @RequestHeader(value = "X-Reviewer-Id", required = false) String reviewer,
                                  @RequestHeader(value = "X-Reviewer-Roles", required = false) String roles) {
        return handle(id, new ApprovalCommand.Reject(d.reasonCode(), d.note()), identity(reviewer, roles, d.decidedBy()));
    }

    @PostMapping("/{id}/reconcile")
    public ApprovalRequest reconcile(@PathVariable String id,
                                     @RequestHeader(value = "X-Reviewer-Id", required = false) String reviewer,
                                     @RequestHeader(value = "X-Reviewer-Roles", required = false) String roles) {
        try {
            ReviewerIdentity who = identity(reviewer, roles, io.github.chdurai.agenticbanking.security.CallerContext.current().actor());
            if (!props.isEnforceRoles() && who.roles().isEmpty()
                    && io.github.chdurai.agenticbanking.security.CallerContext.current().equals(io.github.chdurai.agenticbanking.security.CallerContext.DEV)) {
                ApprovalRequest current = queue.get(id);
                if (current != null) who = new ReviewerIdentity(who.id(), Set.of(current.requiredRole()));
            }
            return queue.reconcile(id, who);
        } catch (ApprovalDeniedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{id}/override")
    public ApprovalRequest override(@PathVariable String id, @RequestBody Override o,
                                    @RequestHeader(value = "X-Reviewer-Id", required = false) String reviewer,
                                    @RequestHeader(value = "X-Reviewer-Roles", required = false) String roles) {
        return handle(id, new ApprovalCommand.OverrideDecision(o.reasonCode(), o.reason()),
                identity(reviewer, roles, o.decidedBy()));
    }

    @PostMapping("/{id}/blind-first")
    public ApprovalRequest blindFirst(@PathVariable String id, @RequestBody BlindFirstDecision d,
                                      @RequestHeader(value = "X-Reviewer-Id", required = false) String reviewer,
                                      @RequestHeader(value = "X-Reviewer-Roles", required = false) String roles) {
        try {
            ReviewerIdentity who = identity(reviewer, roles, d.decidedBy());
            return queue.captureBlindFirst(id, d.decision(), d.reasonCode(), d.note(), who);
        } catch (ApprovalDeniedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Reviewer identity comes from the trusted gateway headers (X-Reviewer-Id / X-Reviewer-Roles). In dev mode
     * the body's decidedBy is accepted as a fallback; with platform.hitl.auth=required it never is.
     */
    private ReviewerIdentity identity(String headerId, String headerRoles, String bodyId) {
        String id = headerId != null && !headerId.isBlank() ? headerId : null;
        if (id == null) {
            if (props.authRequired()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated reviewer required");
            }
            id = bodyId;
        }
        Set<String> roles = headerRoles == null ? Set.of()
                : Arrays.stream(headerRoles.split(",")).map(String::trim).filter(r -> !r.isEmpty())
                .collect(Collectors.toSet());
        return new ReviewerIdentity(id, roles);
    }

    private ApprovalRequest handle(String id, ApprovalCommand command, ReviewerIdentity reviewer) {
        try {
            if (!props.isEnforceRoles() && reviewer.roles().isEmpty()
                    && io.github.chdurai.agenticbanking.security.CallerContext.current().equals(io.github.chdurai.agenticbanking.security.CallerContext.DEV)) {
                ApprovalRequest current = queue.get(id);
                if (current != null) {
                    reviewer = new ReviewerIdentity(reviewer.id(), Set.of(current.requiredRole()));
                }
            }
            return queue.handle(id, command, reviewer);
        } catch (ApprovalDeniedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private ApprovalRequest redactForBlindFirst(ApprovalRequest r) {
        if (r == null || !r.blindFirstRequired() || r.blindFirstAssessment() != null) {
            return r;
        }
        return new ApprovalRequest(
                r.id(), r.flow(), r.subjectId(), r.title(),
                List.of("BLIND_FIRST_REQUIRED: submit an independent assessment before viewing AI evidence."),
                r.evidencePacket() == null ? null : r.evidencePacket().redactedForBlindFirst(),
                r.status(), r.createdAt(), r.decidedAt(), r.decidedBy(), r.reasonCode(), r.note(), r.executionLog(),
                r.requestedBy(), r.requiredRole(), r.expiresAt(), r.escalatedAt(),
                r.blindFirstRequired(), r.blindFirstAssessment(), r.reasonCodeRequired(),
                r.action(), r.version());
    }
}
