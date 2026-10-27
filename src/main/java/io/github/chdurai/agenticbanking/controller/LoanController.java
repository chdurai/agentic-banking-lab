package io.github.chdurai.agenticbanking.controller;

import jakarta.validation.Valid;
import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.cases.CaseStoreExceptions;
import io.github.chdurai.agenticbanking.memory.CaseMemoryStore;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.orchestrator.LoanOrchestrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/loans")
public class LoanController {

    private final LoanOrchestrator orchestrator;
    private final CaseMemoryStore memory;
    private final CaseService cases;
    private final boolean idempotencyRequired;

    public LoanController(LoanOrchestrator orchestrator, CaseMemoryStore memory, CaseService cases,
                          @Value("${platform.idempotency.required:false}") boolean idempotencyRequired) {
        this.orchestrator = orchestrator;
        this.memory = memory;
        this.cases = cases;
        this.idempotencyRequired = idempotencyRequired;
    }

    /**
     * With an Idempotency-Key header the call is durable and replay-safe (same key + body returns the stored
     * decision without re-running agents; same key + different body is 409). Required when
     * platform.idempotency.required=true.
     */
    @PostMapping("/evaluate")
    public ResponseEntity<LoanDecision> evaluate(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody LoanApplication application) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            if (idempotencyRequired) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
            }
            idempotencyKey = java.util.UUID.randomUUID().toString();
        }
        try {
            CaseService.Outcome<LoanDecision> out = cases.runIdempotent("loan", idempotencyKey,
                    application.applicantId(), application, LoanDecision.class, "loan.case.completed",
                    () -> orchestrator.evaluate(application));
            return ResponseEntity.ok().header("X-Case-Id", out.caseId())
                    .header("Idempotent-Replayed", String.valueOf(out.replayed())).body(out.result());
        } catch (CaseStoreExceptions.IdempotencyConflictException | CaseStoreExceptions.InProgressException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** Case memory: full decision history for an applicant. */
    @GetMapping("/history/{applicantId}")
    public List<LoanDecision> history(@PathVariable String applicantId) {
        return memory.historyFor(applicantId);
    }

    /** Convenience: a known-good sample application (bureau profile A001, FICO 782). */
    @GetMapping("/sample")
    public LoanApplication sample() {
        return new LoanApplication(
                "A001", "Jane Cooper", "4321",
                new BigDecimal("145000"), 8,
                new BigDecimal("35000"), "home renovation", 60,
                new BigDecimal("1200"), "Acme Corp",
                List.of("paystubs.pdf", "id.pdf", "bank-statements.pdf"));
    }
}
