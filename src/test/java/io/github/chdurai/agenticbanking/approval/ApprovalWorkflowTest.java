package io.github.chdurai.agenticbanking.approval;

import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalWorkflowTest {

    private final List<PlatformEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicInteger runs = new AtomicInteger();
    private boolean failAction;
    private HitlProperties props;

    private final ApprovalActionHandler handler = new ApprovalActionHandler() {
        public String type() { return "test.run"; }
        public List<String> execute(ApprovalRequest r) {
            runs.incrementAndGet();
            if (failAction) {
                throw new IllegalStateException("boom");
            }
            return List.of("ran " + r.subjectId());
        }
    };

    @BeforeEach
    void setUp() {
        props = new HitlProperties();
        props.setEnforceRoles(true);
    }

    private ApprovalQueue queue(ApprovalStore store) {
        var publisher = new PlatformEventPublisher(e -> events.add((PlatformEvent) e));
        return new ApprovalQueue(new AgentMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                publisher, store, List.of(handler), List.of(new LoanDecisionResumeListener(publisher)), props);
    }

    private static ReviewerIdentity uw(String id) {
        return new ReviewerIdentity(id, Set.of(Roles.LOAN_UNDERWRITER));
    }

    private static ActionSpec action() {
        return new ActionSpec("test.run", java.util.Map.of());
    }

    @Test
    void approveRunsActionOnceAndRecordsResultSeparately() {
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("loan", "A1", "t", List.of(), action());
        var done = q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "ok"), uw("alice"));
        assertThat(done.status()).isEqualTo(ApprovalRequest.Status.APPROVED);
        assertThat(done.executionLog()).containsExactly("ran A1");
        assertThatThrownBy(() -> q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "again"), uw("bob")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runs).hasValue(1);
        assertThat(events).anyMatch(e -> e.type().equals("loan.final"));
    }

    @Test
    void failedExecutionIsADifferentFactFromTheApproval() {
        failAction = true;
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("loan", "A2", "t", List.of(), action());
        var done = q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "ok"), uw("alice"));
        assertThat(done.status()).isEqualTo(ApprovalRequest.Status.EXECUTION_FAILED);
        assertThat(done.decidedBy()).isEqualTo("alice");
        assertThat(done.executionLog().get(0)).contains("boom");
    }

    @Test
    void rejectedLoanEmitsAdverseActionAndRunsNothing() {
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("loan", "A3", "t", List.of(), action());
        q.handle(r.id(), new ApprovalCommand.Reject("UW_REJECT", "no"), uw("alice"));
        assertThat(runs).hasValue(0);
        assertThat(events).extracting(PlatformEvent::type).contains("loan.final", "loan.adverse_action");
    }

    @Test
    void overrideRequiresReasonAndDoesNotRunTheAction() {
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("loan", "A4", "t", List.of(), action());
        assertThatThrownBy(() -> q.handle(r.id(), new ApprovalCommand.OverrideDecision(" ", " "), uw("alice")))
                .isInstanceOf(IllegalArgumentException.class);
        var done = q.handle(r.id(),
                new ApprovalCommand.OverrideDecision("UW_OVERRIDE", "customer provided new documents"), uw("alice"));
        assertThat(done.status()).isEqualTo(ApprovalRequest.Status.OVERRIDDEN);
        assertThat(runs).hasValue(0);
    }

    @Test
    void requesterCannotApproveOwnRequest() {
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("loan", "A5", "t", List.of(), null, "carol", null);
        assertThatThrownBy(() -> q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "x"), uw("carol")))
                .isInstanceOf(ApprovalDeniedException.class);
        assertThat(q.get(r.id()).status()).isEqualTo(ApprovalRequest.Status.PENDING);
    }

    @Test
    void wrongRoleIsDeniedAndAdminAllowed() {
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("grievance", "G1", "t", List.of(), null, null, Roles.SENSITIVE_CASE_TEAM);
        assertThatThrownBy(() -> q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "x"),
                new ReviewerIdentity("dave", Set.of(Roles.GRM_OFFICER)))).isInstanceOf(ApprovalDeniedException.class);
        var done = q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "x"),
                new ReviewerIdentity("erin", Set.of(Roles.SENSITIVE_CASE_TEAM)));
        assertThat(done.status()).isEqualTo(ApprovalRequest.Status.APPROVED);
    }

    @Test
    void concurrentDecisionsHaveExactlyOneWinner() throws Exception {
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("loan", "A6", "t", List.of(), action());
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String who = "u" + i;
            fs.add(pool.submit(() -> {
                go.await();
                try {
                    q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "x"), uw(who));
                    wins.incrementAndGet();
                } catch (IllegalStateException expected) { }
                return null;
            }));
        }
        go.countDown();
        for (var f : fs) f.get();
        pool.shutdown();
        assertThat(wins).hasValue(1);
        assertThat(runs).hasValue(1);
    }

    @Test
    void escalatesThenExpiresWithSafeDefault() {
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("loan", "A7", "t", List.of(), action());
        Instant mid = r.createdAt().plus(Duration.between(r.createdAt(), r.expiresAt()).dividedBy(2)).plusSeconds(1);
        assertThat(q.sweep(mid)).isEqualTo(1);
        assertThat(q.get(r.id()).escalatedAt()).isNotNull();
        assertThat(q.sweep(mid)).isZero();
        assertThat(q.sweep(r.expiresAt().plusSeconds(1))).isEqualTo(1);
        assertThat(q.get(r.id()).status()).isEqualTo(ApprovalRequest.Status.EXPIRED);
        assertThat(runs).hasValue(0);
        assertThat(events).extracting(PlatformEvent::type).contains("approval.escalated", "approval.expired");
        assertThatThrownBy(() -> q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "late"), uw("alice")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void pendingApprovalSurvivesRestart() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:appr" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbc);

        var before = queue(new JdbcApprovalStore(jdbc));
        var r = before.submit("loan", "A8", "t", List.of("d1"), action());

        var after = queue(new JdbcApprovalStore(jdbc)); // "restart": fresh service, same database
        assertThat(after.list(ApprovalRequest.Status.PENDING)).extracting(ApprovalRequest::id).contains(r.id());
        var done = after.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "ok"), uw("alice"));
        assertThat(done.status()).isEqualTo(ApprovalRequest.Status.APPROVED);
        assertThat(done.executionLog()).containsExactly("ran A8");
        assertThat(after.get(r.id()).status()).isEqualTo(ApprovalRequest.Status.APPROVED);
    }

    @Test
    void nonTrivialApprovalRequiresReasonCode() {
        var q = queue(new InMemoryApprovalStore());
        ApprovalEvidencePacket packet = new ApprovalEvidencePacket(1, "loan", "A10",
                List.of("applicationId=A10"),
                List.of(new ApprovalEvidencePacket.AgentFinding("decision", "WARN", 82, "risk high", List.of())),
                List.of("policyVersion=loan-v1"), List.of(), "n/a", List.of(),
                new ApprovalEvidencePacket.SideEffectPreview(List.of("Finalize loan"), List.of("Keep manual review")),
                List.of("High risk"), "MANUAL_REVIEW", 82, true);
        var r = q.submit("loan", "A10", "t", List.of("d"), packet);

        assertThatThrownBy(() -> q.handle(r.id(), new ApprovalCommand.Approve(" ", "ok"), uw("alice")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(q.handle(r.id(), new ApprovalCommand.Approve("UW_HIGH_RISK", "ok"), uw("alice")).status())
                .isEqualTo(ApprovalRequest.Status.APPROVED);
    }

    @Test
    void blindFirstSampleMustBeCapturedBeforeFinalDecision() {
        props.setBlindFirstSampleRate(1.0);
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("loan", "A11", "t", List.of("d"));
        assertThat(r.blindFirstRequired()).isTrue();

        assertThatThrownBy(() -> q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "ok"), uw("alice")))
                .isInstanceOf(IllegalStateException.class);

        ApprovalRequest withBlind = q.captureBlindFirst(r.id(), BlindFirstAssessment.Decision.APPROVE,
                "UW_INITIAL", "independent first read", uw("alice"));
        assertThat(withBlind.blindFirstAssessment()).isNotNull();
        assertThat(withBlind.blindFirstAssessment().reasonCode()).isEqualTo("UW_INITIAL");

        ApprovalRequest done = q.handle(r.id(), new ApprovalCommand.Approve("UW_APPROVE", "ok"), uw("alice"));
        assertThat(done.status()).isEqualTo(ApprovalRequest.Status.APPROVED);
    }

    @Test
    void devModeDoesNotEnforceRoles() {
        props = new HitlProperties();
        var q = queue(new InMemoryApprovalStore());
        var r = q.submit("loan", "A9", "t", List.of());
        assertThat(q.decide(r.id(), true, "anyone", "ok").status()).isEqualTo(ApprovalRequest.Status.APPROVED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void tenantOwnershipCoversApprovalAssociationReadsAndCompareAndSwap(boolean durable) {
        ApprovalStore store;
        if (durable) {
            var ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:tenantApproval" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            var jdbc = new JdbcTemplate(ds);
            io.github.chdurai.agenticbanking.cases.SchemaMigrator.migrate(jdbc);
            store = new JdbcApprovalStore(jdbc);
        } else store = new InMemoryApprovalStore();
        var q = queue(store);
        ApprovalRequest row;
        try (var caller = new io.github.chdurai.agenticbanking.security.CallerContext("alpha", "alice", Set.of(Roles.LOAN_UNDERWRITER)).open();
             var execution = io.github.chdurai.agenticbanking.cases.CaseExecution.open("workflow-alpha")) {
            row = q.submit("loan", "shared-subject", "Review", List.of());
            assertThat(store.forCase("workflow-alpha")).extracting(ApprovalRequest::id).containsExactly(row.id());
        }
        try (var caller = new io.github.chdurai.agenticbanking.security.CallerContext("beta", "bob", Set.of(Roles.PLATFORM_ADMIN)).open()) {
            assertThat(q.get(row.id())).isNull();
            assertThat(store.list(null, 10)).isEmpty();
            assertThat(store.forCase("workflow-alpha")).isEmpty();
            assertThat(store.replace(row, row.decided(ApprovalRequest.Status.REJECTED, "bob", "NO", "", Instant.now()))).isFalse();
        }
        try (var caller = new io.github.chdurai.agenticbanking.security.CallerContext("alpha", "reader", Set.of("KNOWLEDGE_READER")).open()) {
            props.setEnforceRoles(false);
            assertThatThrownBy(() -> q.decide(row.id(), true, "spoofed", "ok")).isInstanceOf(ApprovalDeniedException.class);
        }
        try (var caller = new io.github.chdurai.agenticbanking.security.CallerContext("alpha", "alice", Set.of(Roles.LOAN_UNDERWRITER)).open()) {
            assertThat(q.handleVersioned(row.id(), 0, new ApprovalCommand.Approve("REVIEWED", ""),
                    uw("spoofed")).decidedBy()).isEqualTo("alice");
        }
    }
}
