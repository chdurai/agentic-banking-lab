package io.github.chdurai.agenticbanking.controller;

import io.github.chdurai.agenticbanking.approval.*;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalDecisionController {
    public record Decision(String decision, String reasonCode, String comment) {}
    private final ApprovalQueue approvals;
    public ApprovalDecisionController(ApprovalQueue approvals) { this.approvals = approvals; }

    @PostMapping("/{id}/decisions")
    public ResponseEntity<ApprovalRequest> decide(@PathVariable String id, @RequestHeader("If-Match") String version,
                                                 @RequestBody Decision body) {
        ApprovalRequest row = approvals.get(id);
        if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found");
        var caller = CallerContext.current();
        if (!caller.roles().contains(Roles.PLATFORM_ADMIN) && !caller.roles().contains(row.requiredRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Required approval role missing");
        }
        if (!version.equals("\"" + row.version() + "\"")) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Approval version does not match");
        }
        if (body.reasonCode() == null || body.reasonCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reasonCode required");
        }
        ApprovalCommand command = switch (body.decision() == null ? "" : body.decision()) {
            case "APPROVE" -> new ApprovalCommand.Approve(body.reasonCode(), body.comment());
            case "REJECT" -> new ApprovalCommand.Reject(body.reasonCode(), body.comment());
            case "OVERRIDE" -> new ApprovalCommand.OverrideDecision(body.reasonCode(), body.comment());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown decision");
        };
        try {
            var result = approvals.handleVersioned(id, row.version(), command, new ReviewerIdentity(caller.actor(),
                    caller.roles()));
            return ResponseEntity.ok().eTag(Long.toString(result.version())).body(result);
        } catch (ApprovalDeniedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Decision forbidden");
        } catch (io.github.chdurai.agenticbanking.cases.CaseStoreExceptions.StaleVersionException e) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Approval version does not match");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Approval changed or cannot be decided");
        }
    }
}
