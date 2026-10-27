package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.AgenticBankingApplication;
import io.github.chdurai.agenticbanking.approval.*;
import io.github.chdurai.agenticbanking.controller.ApprovalController;
import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;
import io.github.chdurai.agenticbanking.orchestrator.LoanOrchestrator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Real Spring wiring and specialist graph; deterministic model and synthetic tools only, no HTTP listener. */
public final class LoanRecoveryFlowProbe {
    private LoanRecoveryFlowProbe() {}

    public static void verify(boolean enabled) {
        var model = new StubChatModel();
        model.decisions.clear();
        model.decisions.add(StubChatModel.decision("MANUAL_REVIEW"));
        try (var context = new SpringApplicationBuilder(AgenticBankingApplication.class)
                .web(WebApplicationType.NONE)
                .initializers(c -> ((GenericApplicationContext) c).registerBean("recoveryProbeModel", ChatModel.class, () -> model))
                .run("--spring.ai.model.chat=none", "--spring.ai.model.embedding=none",
                        "--spring.ai.mcp.client.enabled=false", "--spring.ai.mcp.server.enabled=false",
                        "--agents.anomaly.enabled=false", "--platform.cases.worker-enabled=false",
                        "--platform.incident.ingestion.worker-interval-ms=3600000",
                        "--platform.persistence=jdbc", "--platform.hitl.enforce-roles=true",
                        "--platform.loan.recovery-simulation=" + enabled,
                        "--platform.datasource.url=jdbc:h2:mem:loan-flow-" + UUID.randomUUID(),
                        "--logging.level.root=WARN", "--spring.main.banner-mode=off")) {
            var application = new LoanApplication("SYNTHETIC-" + UUID.randomUUID(), "Synthetic Applicant", "4321",
                    new BigDecimal("145000"), 8, new BigDecimal("35000"), "home renovation", 60,
                    new BigDecimal("1200"), "Synthetic Employer", List.of("paystubs.pdf", "id.pdf", "bank-statements.pdf"));
            var caseStore = context.getBean(io.github.chdurai.agenticbanking.cases.CaseStore.class);
            var durableCase = caseStore.claim("loan", application.applicantId(), "synthetic", "synthetic");
            LoanDecision result;
            try (var execution = io.github.chdurai.agenticbanking.cases.CaseExecution.open(durableCase.record().caseId())) {
                result = context.getBean(LoanOrchestrator.class).evaluate(application);
            }
            check(result.decision() == LoanDecision.Decision.MANUAL_REVIEW, "Expected human review");
            var approvals = context.getBean(ApprovalQueue.class);
            var pending = approvals.list(ApprovalRequest.Status.PENDING).stream()
                    .filter(r -> r.subjectId().equals(application.applicantId())).findFirst().orElseThrow();
            check(pending.evidencePacket().agentFindings().size() == 4, "Specialist evidence missing");
            check(enabled == (pending.action() != null), "Feature gate changed default action behavior");
            int calls = model.totalCalls();
            var approved = approvals.handle(pending.id(), new ApprovalCommand.Approve("SYNTHETIC_REVIEW", "Fixture only"),
                    new ReviewerIdentity("underwriter", Set.of(Roles.LOAN_UNDERWRITER)));
            check(approved.status() == ApprovalRequest.Status.APPROVED, "Action failed: " + approved.executionLog());
            if (enabled) {
                check(approved.executionLog().contains("SIMULATED_ONLY: no funds moved"), "Simulation receipt missing");
                var reconciled = context.getBean(ApprovalController.class).reconcile(pending.id(), "underwriter",
                        Roles.LOAN_UNDERWRITER);
                check(reconciled.executionLog().equals(approved.executionLog()), "Reconciliation changed receipt");
            }
            check(model.totalCalls() == calls, "Approval/recovery must not replay model calls");
            int receipts = java.util.Objects.requireNonNull(context.getBean(JdbcTemplate.class)
                    .queryForObject("SELECT COUNT(*) FROM simulated_loan_receipt", Integer.class));
            check(receipts == (enabled ? 1 : 0), "Unexpected effect count: " + receipts);
            var executions = context.getBean(io.github.chdurai.agenticbanking.controller.ExecutionJournalController.class)
                    .executions(durableCase.record().caseId());
            check(executions.size() == 1 && executions.getFirst().run().status().equals("COMPLETED")
                    && executions.getFirst().attempts().size() == 4, "Spring runtime journal not connected");
            var alerts = context.getBean(io.github.chdurai.agenticbanking.incident.AlertIngestionService.class);
            var signal = new io.github.chdurai.agenticbanking.incident.AlertSignal("Synthetic DB pool alert", "Database pool exhausted",
                    "loan-service", "staging", "synthetic", "db_pool", List.of("synthetic"), java.time.Instant.now());
            var alert = alerts.ingest(signal);
            check(alert.accepted(), "Alert rejected unexpectedly");
            check(alerts.receipt(alert.alertId()).orElseThrow().status().equals("PENDING"), "Alert not durable at acknowledgement");
            alerts.drain();
            check(alerts.receipt(alert.alertId()).orElseThrow().status().equals("COMPLETED"), "Durable alert dispatch failed");
            int incidentCalls = model.totalCalls();
            var duplicate = alerts.ingest(signal);
            alerts.drain();
            check(alerts.receipt(duplicate.alertId()).orElseThrow().status().equals("CORRELATED"), "Durable correlation not wired");
            check(model.totalCalls() == incidentCalls, "Correlated alert repeated analysis");
            System.out.println("PASS Spring loan flow: simulation=" + enabled
                    + ", four specialists, frozen packet, human approval, receipts=" + receipts
                    + ", no model replay, durable node journal, alert dispatch/correlation");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        verify(false);
        verify(true);
    }
}
