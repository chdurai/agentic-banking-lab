package io.github.chdurai.agenticbanking.cases;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The same behavioural contract must hold for the in-memory and JDBC stores. */
class CaseStoreContractTest {

    enum Impl { MEMORY, JDBC }

    private static CaseStore create(Impl impl) {
        if (impl == Impl.MEMORY) {
            return new InMemoryCaseStore();
        }
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:cases" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaMigrator.migrate(jdbc);
        SchemaMigrator.migrate(jdbc); // idempotent
        return new JdbcCaseStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));
    }

    private static OutboxMessage out(String caseId) {
        return new OutboxMessage(0, caseId, "loan.case.completed", "loan", "sub_x", "{}", 0, null);
    }

    @ParameterizedTest @EnumSource(Impl.class)
    void queueCountIsTenantScopedAndExcludesSynchronousAndClaimedCases(Impl impl) {
        CaseStore store = create(impl);
        try (var ignored = new io.github.chdurai.agenticbanking.security.CallerContext("a", "tester", java.util.Set.of("PLATFORM_ADMIN")).open()) {
            store.claim("loan", "sync", "hash", "sub");
            var first = store.enqueue("loan", "one", "hash", "sub", "{}").record();
            store.enqueue("loan", "two", "hash", "sub", "{}");
            assertThat(store.queued(1)).hasSize(1);
            assertThat(store.countQueued()).isEqualTo(2);
            store.transition(first.caseId(), 0, CaseStatus.RUNNING, null, "worker", "started", null);
            assertThat(store.countQueued()).isEqualTo(1);
        }
        assertThat(store.countQueued()).isZero();
    }

    @ParameterizedTest @EnumSource(Impl.class)
    void claimIsIdempotentAndDetectsBodyConflict(Impl impl) {
        CaseStore s = create(impl);
        CaseStore.Claim a = s.claim("loan", "k1", "h1", "sub_1");
        CaseStore.Claim b = s.claim("loan", "k1", "h1", "sub_1");

        assertThat(a.created()).isTrue();
        assertThat(b.created()).isFalse();
        assertThat(b.record().caseId()).isEqualTo(a.record().caseId());
        assertThatThrownBy(() -> s.claim("loan", "k1", "DIFFERENT", "sub_1"))
                .isInstanceOf(CaseStoreExceptions.IdempotencyConflictException.class);
        assertThat(s.claim("incident", "k1", "h1", "sub_1").created()).isTrue();
        assertThat(s.countCases()).isEqualTo(2);
    }

    @ParameterizedTest @EnumSource(Impl.class)
    void transitionUsesOptimisticLockingAndAppendsEventAndOutbox(Impl impl) {
        CaseStore s = create(impl);
        CaseRecord c = s.claim("loan", "k", "h", "sub_1").record();

        CaseRecord done = s.transition(c.caseId(), 0, CaseStatus.COMPLETED, "{\"ok\":true}", "system",
                "loan.case.completed", out(c.caseId()));

        assertThat(done.version()).isEqualTo(1);
        assertThat(done.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(s.events(c.caseId())).extracting(CaseEvent::type, CaseEvent::seq)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("case.opened", 1L),
                        org.assertj.core.groups.Tuple.tuple("loan.case.completed", 2L));
        assertThat(s.pendingOutbox(10)).hasSize(1);
        assertThatThrownBy(() -> s.transition(c.caseId(), 0, CaseStatus.REJECTED, null, "x", "e", null))
                .isInstanceOf(CaseStoreExceptions.StaleVersionException.class);
        assertThat(s.find(c.caseId()).orElseThrow().status()).isEqualTo(CaseStatus.COMPLETED);
    }

    @ParameterizedTest @EnumSource(Impl.class)
    void outboxRetriesUntilPublished(Impl impl) {
        CaseStore s = create(impl);
        CaseRecord c = s.claim("loan", "k", "h", "sub_1").record();
        s.transition(c.caseId(), 0, CaseStatus.COMPLETED, "{}", "system", "t", out(c.caseId()));

        OutboxMessage m = s.pendingOutbox(10).getFirst();
        s.markFailed(m.id());
        assertThat(s.pendingOutbox(10).getFirst().attempts()).isEqualTo(1);
        s.markPublished(m.id());
        assertThat(s.pendingOutbox(10)).isEmpty();
    }

    @ParameterizedTest @EnumSource(Impl.class)
    void concurrentClaimsCreateExactlyOneCase(Impl impl) throws Exception {
        CaseStore s = create(impl);
        AtomicInteger created = new AtomicInteger();
        try (var ex = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                fs.add(ex.submit(() -> {
                    if (s.claim("loan", "same", "h", "sub").created()) {
                        created.incrementAndGet();
                    }
                }));
            }
            for (var f : fs) {
                f.get();
            }
        }
        assertThat(created).hasValue(1);
        assertThat(s.countCases()).isEqualTo(1);
    }

    @Test
    void schemaMigratorRecordsScripts() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mig" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaMigrator.migrate(jdbc);
        assertThat(jdbc.queryForObject("SELECT script FROM schema_version WHERE script LIKE 'V3__%'", String.class))
                .isEqualTo("V3__tenant_cases.sql");
    }

    @ParameterizedTest @EnumSource(Impl.class)
    void tenantsCannotReadMutateOrReplayEachOthersCasesOrOutbox(Impl impl) {
        CaseStore store = create(impl);
        var a = new io.github.chdurai.agenticbanking.security.CallerContext("alpha", "alice", java.util.Set.of("PLATFORM_ADMIN"));
        var b = new io.github.chdurai.agenticbanking.security.CallerContext("beta", "bob", java.util.Set.of("PLATFORM_ADMIN"));
        String id;
        long message;
        try (var ignored = a.open()) {
            var row = store.enqueue("document", "shared-key", "hash", "sub", "{\"content\":\"alpha\"}").record();
            id = row.caseId();
            assertThat(store.queued(10)).containsExactly(row);
            store.transition(id, 0, CaseStatus.COMPLETED, "alpha", "alice", "complete", out(id));
            message = store.pendingOutbox(10).getFirst().id();
        }
        try (var ignored = b.open()) {
            assertThat(store.find(id)).isEmpty();
            assertThat(store.events(id)).isEmpty();
            assertThat(store.page(null, 10)).isEmpty();
            assertThat(store.pendingOutbox(10)).isEmpty();
            assertThat(store.queued(10)).isEmpty();
            assertThatThrownBy(() -> store.transition(id, 1, CaseStatus.REJECTED, "beta", "bob", "e", null))
                    .isInstanceOf(IllegalArgumentException.class);
            store.markPublished(message);
            assertThat(store.claim("document", "shared-key", "other-hash", "sub").created()).isTrue();
            assertThat(store.countCases()).isEqualTo(1);
        }
        try (var ignored = a.open()) {
            assertThat(store.pendingOutbox(10)).hasSize(1);
            assertThat(store.find(id).orElseThrow().resultJson()).isEqualTo("alpha");
        }
    }

    @Test
    void queuedRequestsSurviveRepositoryRecreationAndOnlyOneWorkerCanClaim() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:restart" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        SchemaMigrator.migrate(jdbc);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var original = new JdbcCaseStore(jdbc, tx);
        var row = original.enqueue("document", "queue-key", "hash", "sub", "{\"content\":\"retained\"}").record();
        var restarted = new JdbcCaseStore(new JdbcTemplate(ds), tx);
        assertThat(restarted.queued(10)).extracting(CaseRecord::requestJson).containsExactly("{\"content\":\"retained\"}");
        restarted.transition(row.caseId(), 0, CaseStatus.RUNNING, null, "worker", "started", null);
        assertThatThrownBy(() -> original.transition(row.caseId(), 0, CaseStatus.RUNNING, null, "worker2", "started", null))
                .isInstanceOf(CaseStoreExceptions.StaleVersionException.class);
        assertThat(restarted.queued(10)).isEmpty();
        assertThat(restarted.running(10)).hasSize(1);
        var loan = restarted.claim("loan", "linked-loan", "h", "sub").record();
        var link = restarted.link(row.caseId(), loan.caseId(), CaseLink.Type.DOCUMENT_EVIDENCE).link();
        assertThat(new JdbcCaseStore(jdbc, tx).findLink(link.id())).contains(link);
    }

    @ParameterizedTest @EnumSource(Impl.class)
    void linksAreTypedIdempotentTenantScopedAndDoNotInvalidateRunningWork(Impl impl) {
        var store = create(impl);
        var alpha = new io.github.chdurai.agenticbanking.security.CallerContext("alpha", "alice", java.util.Set.of("PLATFORM_ADMIN"));
        var beta = new io.github.chdurai.agenticbanking.security.CallerContext("beta", "bob", java.util.Set.of("PLATFORM_ADMIN"));
        CaseRecord document;
        CaseRecord loan;
        CaseLink link;
        try (var ignored = alpha.open()) {
            document = store.claim("document", "doc", "hash", "sub").record();
            loan = store.claim("loan", "loan", "hash", "sub").record();
            var creation = store.link(document.caseId(), loan.caseId(), CaseLink.Type.DOCUMENT_EVIDENCE);
            link = creation.link();
            assertThat(creation.created()).isTrue();
            assertThat(link.createdBy()).isEqualTo("alice");
            assertThat(store.link(document.caseId(), loan.caseId(), CaseLink.Type.DOCUMENT_EVIDENCE).created()).isFalse();
            assertThat(store.links(loan.caseId(), null, 5)).containsExactly(link);
            assertThat(store.pendingOutbox(5)).hasSize(1).allSatisfy(m -> assertThat(m.type()).isEqualTo("case.linked"));
            assertThat(store.find(document.caseId()).orElseThrow().version()).isZero();
            assertThatThrownBy(() -> store.link(loan.caseId(), document.caseId(), CaseLink.Type.DOCUMENT_EVIDENCE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        try (var ignored = beta.open()) {
            var other = store.claim("loan", "loan", "hash", "sub").record();
            assertThat(store.findLink(link.id())).isEmpty();
            assertThat(store.links(loan.caseId(), null, 5)).isEmpty();
            assertThatThrownBy(() -> store.link(document.caseId(), other.caseId(), CaseLink.Type.DOCUMENT_EVIDENCE))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(store.pendingOutbox(5)).isEmpty();
        }
        try (var ignored = alpha.open()) {
            store.transition(document.caseId(), 0, CaseStatus.COMPLETED, "{}", "worker", "complete", null);
            assertThat(store.findLink(link.id())).contains(link);
        }
    }

    @ParameterizedTest @EnumSource(Impl.class)
    void concurrentLinkCreationPublishesOnlyOneOutboxEvent(Impl impl) throws Exception {
        var store = create(impl);
        var source = store.claim("document", "document", "h", "sub").record();
        var target = store.claim("loan", "loan", "h", "sub").record();
        var created = new AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    if (store.link(source.caseId(), target.caseId(), CaseLink.Type.DOCUMENT_EVIDENCE).created()) created.incrementAndGet();
                }));
            }
            for (var future : futures) future.get();
        }
        assertThat(created).hasValue(1);
        assertThat(store.links(target.caseId(), null, 20)).hasSize(1);
        assertThat(store.pendingOutbox(20)).hasSize(1);
    }
}
