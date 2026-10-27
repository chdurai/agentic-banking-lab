package io.github.chdurai.agenticbanking.approval;

import io.github.chdurai.agenticbanking.cases.SchemaMigrator;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static io.github.chdurai.agenticbanking.approval.LoanRecoveryProcessProbe.*;

class LoanRecoveryTest {
    JdbcTemplate jdbc;
    JdbcApprovalStore store;
    SimulatedLoanBooking booking;

    @BeforeEach void database() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:recovery-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        SchemaMigrator.migrate(jdbc);
        store = new JdbcApprovalStore(jdbc);
        booking = new SimulatedLoanBooking(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    @Test void receiptIsStableAcrossDuplicateAndConcurrentReconciliation() throws Exception {
        var queue = queue(jdbc, booking);
        var pending = submit(queue);
        var approved = pending.decided(ApprovalRequest.Status.APPROVED, "underwriter", "REVIEWED", "ok", java.time.Instant.now());
        assertThat(store.replace(pending, approved)).isTrue();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = pool.invokeAll(java.util.stream.IntStream.range(0, 12)
                    .<java.util.concurrent.Callable<List<String>>>mapToObj(i -> () -> queue.reconcile(pending.id()).executionLog())
                    .toList());
            var log = results.getFirst().get();
            assertThat(log).hasSize(4).contains("SIMULATED_ONLY: no funds moved");
            for (var result : results) assertThat(result.get()).isEqualTo(log);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loan_receipt", Integer.class)).isOne();
    }

    @Test void executionRequiresApprovalAndOriginalEvidence() {
        var pending = submit(queue(jdbc, booking));
        assertThatThrownBy(() -> booking.execute(pending)).hasMessageContaining("original approved");
        var changed = new ApprovalEvidencePacket(1, "loan", pending.subjectId(), List.of("changed"),
                List.of(), List.of(), List.of(), "", List.of(), null, List.of(), "", 0, false);
        var tampered = new ApprovalRequest(pending.id(), pending.flow(), pending.subjectId(), pending.title(),
                pending.details(), changed, pending.status(), pending.createdAt(), pending.decidedAt(), pending.decidedBy(),
                pending.reasonCode(), pending.note(), pending.executionLog(), pending.requestedBy(), pending.requiredRole(),
                pending.expiresAt(), pending.escalatedAt(), pending.blindFirstRequired(), pending.blindFirstAssessment(),
                pending.reasonCodeRequired(), pending.action(), pending.version() + 1);
        assertThatThrownBy(() -> store.replace(pending, tampered)).hasMessageContaining("cannot change");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loan_receipt", Integer.class)).isZero();
    }

    @Test void evidenceCorruptionIsExplicitAndCannotExecute() {
        var pending = submit(queue(jdbc, booking));
        jdbc.update("UPDATE approval_evidence_seal SET sha256=?", "0".repeat(64));
        assertThatThrownBy(() -> store.find(pending.id())).hasMessageContaining("integrity");
        assertThatThrownBy(() -> booking.execute(pending)).hasMessageContaining("integrity");
    }

    @Test void missingSealAndIncompleteEvidenceCannotProduceAReceipt() {
        var queue = queue(jdbc, booking);
        var missing = submit(queue);
        jdbc.update("DELETE FROM approval_evidence_seal WHERE approval_id=?", missing.id());
        var outcome = queue.handle(missing.id(), new ApprovalCommand.Approve("REVIEWED", "ok"), reviewer());
        assertThat(outcome.status()).isEqualTo(ApprovalRequest.Status.EXECUTION_FAILED);
        assertThat(outcome.executionLog().getFirst()).contains("Frozen evidence is missing");

        var legacy = queue.submit("loan", "legacy", "Incomplete packet", List.of(), SimulatedLoanBooking.action());
        var blocked = queue.handle(legacy.id(), new ApprovalCommand.Approve("REVIEWED", "ok"), reviewer());
        assertThat(blocked.status()).isEqualTo(ApprovalRequest.Status.EXECUTION_FAILED);
        assertThat(blocked.executionLog().getFirst()).contains("Incomplete");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loan_receipt", Integer.class)).isZero();
    }

    @Test void wrongTenantAndWrongRoleCannotReconcile() {
        var queue = queue(jdbc, booking);
        var pending = submit(queue);
        var approved = queue.handle(pending.id(), new ApprovalCommand.Approve("REVIEWED", "ok"), reviewer());
        try (var ignored = new CallerContext("other", "officer", Set.of(Roles.PLATFORM_ADMIN)).open()) {
            assertThat(store.find(pending.id())).isNull();
            assertThatThrownBy(() -> booking.execute(approved)).hasMessageContaining("unavailable");
            assertThatThrownBy(() -> queue.reconcile(pending.id())).hasMessageContaining("Unknown approval");
        }
        try (var ignored = new CallerContext("dev", "officer", Set.of(Roles.GRM_OFFICER)).open()) {
            assertThatThrownBy(() -> queue.reconcile(pending.id())).isInstanceOf(ApprovalDeniedException.class);
        }
    }

    @Test void rejectionAndOrdinaryHandlersAreNeverReplayed() {
        var queue = queue(jdbc, booking);
        var pending = submit(queue);
        queue.handle(pending.id(), new ApprovalCommand.Reject("REJECTED", "no"), reviewer());
        assertThatThrownBy(() -> queue.reconcile(pending.id())).hasMessageContaining("no recoverable");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loan_receipt", Integer.class)).isZero();
        ApprovalActionHandler ordinary = new ApprovalActionHandler() {
            public String type() { return "ordinary"; }
            public List<String> execute(ApprovalRequest request) { return List.of("done"); }
        };
        var ordinaryQueue = queue(jdbc, ordinary);
        var other = ordinaryQueue.submit("loan", "other", "Ordinary", List.of(),
                new ActionSpec("ordinary", java.util.Map.of()));
        ordinaryQueue.handle(other.id(), new ApprovalCommand.Approve("REVIEWED", "ok"), reviewer());
        assertThatThrownBy(() -> ordinaryQueue.reconcile(other.id())).hasMessageContaining("no recoverable");
    }

    @Test void approvalAndSealCommitAtomically() {
        jdbc.execute("ALTER TABLE approval_evidence_seal ADD CONSTRAINT reject_seal CHECK (schema_version=99)");
        assertThatThrownBy(() -> submit(queue(jdbc, booking))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approvals", Integer.class)).isZero();
    }
}
