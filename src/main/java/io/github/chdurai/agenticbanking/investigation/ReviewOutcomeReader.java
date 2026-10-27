package io.github.chdurai.agenticbanking.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
import io.github.chdurai.agenticbanking.approval.JdbcApprovalStore;
import io.github.chdurai.agenticbanking.approval.SimulatedLoanBooking;
import io.github.chdurai.agenticbanking.cases.CaseService;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;

/** Read-only join: a later human outcome never rewrites the frozen analysis passport. */
public final class ReviewOutcomeReader {
    private final InvestigationStore evidence;
    private final JdbcTemplate jdbc;
    private final JdbcApprovalStore approvals;
    private final ObjectMapper json;
    private final TransactionTemplate read;

    public ReviewOutcomeReader(InvestigationStore evidence, JdbcTemplate jdbc, ObjectMapper json) {
        this.evidence = evidence;
        this.jdbc = jdbc;
        this.json = json;
        this.approvals = new JdbcApprovalStore(jdbc);
        this.read = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Map<String, Object> view(String caseId) {
        return Objects.requireNonNull(read.execute(status -> {
            var passport = evidence.passport(caseId);
            JsonNode snapshot = json.valueToTree(passport.get("snapshot"));
            String runId = snapshot.path("investigation").path("run").path("run_id").asText();
            if (runId.isBlank()) throw new IllegalStateException("Frozen investigation identity missing");
            var requests = approvals.forCase(caseId);
            if (requests.size() > 20) throw new IllegalStateException("Too many review checkpoints for a bounded showcase case");
            List<Map<String, Object>> outcomes = requests.stream()
                    .sorted(Comparator.comparing(ApprovalRequest::id))
                    .map(request -> outcome(request, runId)).toList();
            return Map.<String, Object>of("mode", "READ_ONLY_CURRENT_REVIEW_OUTCOME",
                    "caseId", caseId, "passportSha256", passport.get("sha256"), "observedAt", Instant.now().toString(),
                    "reviews", outcomes, "providerCalls", 0, "toolCalls", 0, "mutationCalls", 0,
                    "limits", List.of("This is a current read-only observation, not part of the immutable historical analysis.",
                            "A reviewer decision is not an executed effect. Only a verified local receipt records the database-only simulation.",
                            "No funds moved; no external booking or automatic reconciliation occurs here."));
        }));
    }

    private Map<String, Object> outcome(ApprovalRequest request, String runId) {
        if (!"loan".equals(request.flow()) || request.evidencePacket() == null
                || request.evidencePacket().agentFindings().stream().noneMatch(finding ->
                "loan-investigator".equals(finding.agentId()) && finding.citations().contains("run=" + runId)))
            throw new IllegalStateException("Review evidence does not reference the frozen investigation");
        var seal = approvals.requireSeal(request);
        String tenant = CallerContext.current().tenantId();
        var receipts = jdbc.query("SELECT receipt_id,effect_key,evidence_sha256,recorded_at FROM simulated_loan_receipt WHERE tenant_id=? AND approval_id=?",
                (row, number) -> Map.<String, Object>of("receiptId", row.getString(1), "effectKey", row.getString(2),
                        "approvalEvidenceSha256", row.getString(3), "recordedAt", row.getTimestamp(4).toInstant().toString()),
                tenant, request.id());
        if (receipts.size() > 1) throw new IllegalStateException("Multiple receipts for one simulated action");
        String state;
        if (!receipts.isEmpty()) {
            var receipt = receipts.getFirst();
            String expectedKey = "loan-sim:v1:" + CaseService.sha256(tenant + ":" + request.id() + ":" + seal.sha256());
            if (!SimulatedLoanBooking.action().equals(request.action())
                    || !seal.sha256().equals(receipt.get("approvalEvidenceSha256"))
                    || !expectedKey.equals(receipt.get("effectKey"))
                    || request.decidedAt() == null
                    || !Set.of(ApprovalRequest.Status.APPROVED, ApprovalRequest.Status.EXECUTION_FAILED).contains(request.status()))
                throw new IllegalStateException("Receipt is inconsistent with its sealed authorized simulation");
            state = request.executionLog().contains("receipt=" + receipt.get("receiptId"))
                    ? "SIMULATED_RECEIPT_CONFIRMED" : "SIMULATED_RECEIPT_RECORDED_OUTCOME_NOT_RECONCILED";
        } else {
            if (request.executionLog().stream().anyMatch(line -> line.startsWith("receipt=")))
                throw new IllegalStateException("Recorded simulation receipt is missing; effect cannot be verified");
            state = switch (request.status()) {
                case PENDING -> "AWAITING_HUMAN_REVIEW";
                case REJECTED, OVERRIDDEN, EXPIRED -> "NO_SIMULATED_EFFECT";
                case APPROVED -> request.action() == null ? "APPROVED_WITHOUT_ACTION" : "APPROVED_EFFECT_NOT_CONFIRMED";
                case EXECUTION_FAILED -> "EXECUTION_FAILED_NO_RECEIPT";
            };
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("approvalId", request.id());
        result.put("approvalVersion", request.version());
        result.put("reviewStatus", request.status().name());
        result.put("reviewedAt", request.decidedAt() == null ? null : request.decidedAt().toString());
        result.put("reasonCode", request.reasonCode());
        result.put("approvalEvidenceSha256", seal.sha256());
        result.put("effectState", state);
        result.put("receipt", receipts.isEmpty() ? null : receipts.getFirst());
        return result;
    }
}
