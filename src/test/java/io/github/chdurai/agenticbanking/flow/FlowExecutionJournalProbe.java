package io.github.chdurai.agenticbanking.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.*;
import io.github.chdurai.agenticbanking.flow.plan.DagPlan;
import io.github.chdurai.agenticbanking.flow.plan.PlanResolver;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecRegistry;
import io.github.chdurai.agenticbanking.ops.ExecutionTraceStore;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executable evidence for bounded, non-replaying JDBC node checkpoints. No provider or HTTP listener. */
public final class FlowExecutionJournalProbe {
    private FlowExecutionJournalProbe() {}

    private static JdbcTemplate database(Path path) {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + path);
        return new JdbcTemplate(ds);
    }

    private static TransactionTemplate transactions(JdbcTemplate jdbc) {
        return new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }

    private static JdbcFlowExecutionJournal journal(JdbcTemplate jdbc) {
        return new JdbcFlowExecutionJournal(jdbc, transactions(jdbc), new ObjectMapper().findAndRegisterModules());
    }

    private static String caseId(JdbcTemplate jdbc, String key) {
        return new JdbcCaseStore(jdbc, transactions(jdbc)).claim("loan", key, key, "synthetic").record().caseId();
    }

    private static DagPlan plan() {
        return new PlanResolver(AgentSpecRegistry.withDefaults(), new FlowProperties(), new PlatformEventPublisher(e -> {}))
                .rules("loan");
    }

    private static FlowRuntime runtime(JdbcFlowExecutionJournal journal) {
        var runtime = new FlowRuntime(AgentSpecRegistry.withDefaults(), new ExecutionTraceStore(), new FlowProperties());
        runtime.setJournal(journal);
        return runtime;
    }

    public static void verify(Path directory) throws Exception {
        Files.createDirectories(directory);
        JdbcTemplate jdbc = database(directory.resolve("journal").toAbsolutePath());
        SchemaMigrator.migrate(jdbc);
        var journal = journal(jdbc);
        var plan = plan();
        fails(() -> journal.begin(new DagPlan(plan.flow(), plan.nodes(), plan.edges(), plan.rationale(),
                2, plan.source(), plan.fallbackReason()), null), "Unsupported execution plan");
        String id = caseId(jdbc, "happy");
        var runtime = runtime(journal);
        try (var ignored = CaseExecution.open(id)) {
            var result = runtime.run("synthetic", plan(), (node, inputs) -> node + ":" + inputs.keySet(), null);
            check(result.results().size() == 4 && !result.stopped(), "Runtime parity failed");
            var stored = journal.runs(id, 10).getFirst();
            check(stored.status().equals("COMPLETED"), "Run not committed");
            check(journal.attempts(stored.runId()).size() == 4
                    && journal.attempts(stored.runId()).stream().allMatch(a -> a.status().equals("OK")
                    && a.resultSha256() != null), "Node snapshots not committed");
            var restored = journal(jdbc);
            check(restored.runs(id, 10).equals(journal.runs(id, 10)), "Restart lost run");
            check(restored.attempts(stored.runId()).equals(journal.attempts(stored.runId())), "Restart lost attempts");
            try (var other = new CallerContext("other", "officer", Set.of("PLATFORM_ADMIN")).open()) {
                check(restored.runs(id, 10).isEmpty(), "Cross-tenant run disclosure");
                check(restored.attempts(stored.runId()).isEmpty(), "Cross-tenant finding disclosure");
                fails(() -> restored.begin(plan(), id), "unavailable");
            }
            jdbc.update("UPDATE flow_node_attempt SET result_sha256=? WHERE run_id=? AND node_id='intake'",
                    "0".repeat(64), stored.runId());
            fails(() -> journal.attempts(stored.runId()), "integrity");
        } finally {
            runtime.shutdown();
        }
        String owned = caseId(jdbc, "ownership");
        var owner = journal.begin(plan(), owned);
        journal.started(owner, "intake", List.of());
        fails(() -> journal.begin(plan(), owned), "reconciliation");
        fails(() -> journal.completed(new FlowExecutionJournal.Owner(owner.runId(), owner.tenantId(), "stale"),
                new FlowRuntime.NodeResult<>("intake", FlowRuntime.Status.OK, "value", null, 1)), "stale");
        journal.finished(owner, "UNCERTAIN");
        check(journal.attempts(owner.runId()).getFirst().status().equals("UNCERTAIN"), "Unfinished node falsely completed");
        fails(() -> journal.begin(plan(), owned), "reconciliation");
        fails(() -> journal.started(owner, "kyc", List.of("intake")), "stale");
        verifyCancellation(jdbc);
        verifyStorageFailure(jdbc);
        verifyScheduling(jdbc);
        verifyProcessCrash(directory);
        System.out.println("PASS journal: four committed node snapshots, reconstructed stores, tenant isolation, "
                + "content integrity, exclusive owner, stale-owner/version refusal, interruption, parallel/hard-stop/deadline parity, "
                + "storage-failure propagation, killed JVM");
    }

    private static void verifyCancellation(JdbcTemplate jdbc) throws Exception {
        var journal = journal(jdbc);
        var runtime = runtime(journal);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        String id = caseId(jdbc, "cancel");
        Thread worker = Thread.ofVirtual().start(() -> {
            try (var ignored = CaseExecution.open(id)) {
                runtime.run("synthetic", plan(), (node, inputs) -> {
                    started.countDown();
                    try { Thread.sleep(Duration.ofMinutes(1)); }
                    catch (InterruptedException e) { interrupted.countDown(); throw e; }
                    return node;
                }, null);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        try {
            check(started.await(10, TimeUnit.SECONDS), "Node never started");
            worker.interrupt();
            worker.join(Duration.ofSeconds(10));
            check(!worker.isAlive(), "Runtime ignored cancellation");
            check(failure.get() instanceof java.util.concurrent.CancellationException, "Cancellation not propagated");
            check(interrupted.await(10, TimeUnit.SECONDS), "Active node was not interrupted");
            var run = journal.runs(id, 1).getFirst();
            check(List.of("CANCELLED", "RUNNING").contains(run.status()), "Cancellation falsely reported success");
            fails(() -> journal.begin(plan(), id), "reconciliation");
        } finally {
            worker.interrupt();
            runtime.shutdown();
        }
    }

    private static void verifyStorageFailure(JdbcTemplate jdbc) {
        var journal = journal(jdbc);
        var runtime = runtime(journal);
        String id = caseId(jdbc, "storage-failure");
        jdbc.execute("ALTER TABLE flow_node_attempt ADD CONSTRAINT fail_result CHECK (result_json<>'\"__storage_failure__\"')");
        try (var ignored = CaseExecution.open(id)) {
            fails(() -> runtime.run("synthetic", plan(), (node, inputs) -> "__storage_failure__", null), "Check constraint");
            check(!journal.runs(id, 1).getFirst().status().equals("COMPLETED"), "Storage failure looked successful");
        } finally {
            jdbc.execute("ALTER TABLE flow_node_attempt DROP CONSTRAINT fail_result");
            runtime.shutdown();
        }
    }

    private static void verifyScheduling(JdbcTemplate jdbc) throws Exception {
        var journal = journal(jdbc);
        var runtime = runtime(journal);
        var siblings = new CountDownLatch(2);
        try (var ignored = CaseExecution.open(caseId(jdbc, "parallel"))) {
            var run = runtime.run("synthetic", plan(), (node, inputs) -> {
                if (node.equals("kyc") || node.equals("credit-risk")) {
                    siblings.countDown();
                    check(siblings.await(5, TimeUnit.SECONDS), "Sibling agents became sequential");
                }
                return node;
            }, new FlowRuntime.Options<>(value -> value.equals("kyc"), null, null));
            check(run.stopped() && run.value("credit-risk") != null
                    && run.result("compliance").status() == FlowRuntime.Status.SKIPPED,
                    "Hard-stop discarded siblings or ran downstream work");
        } finally {
            runtime.shutdown();
        }
        var props = new FlowProperties();
        props.setPerNodeDeadlineMs(150);
        var timed = new FlowRuntime(AgentSpecRegistry.withDefaults(), new ExecutionTraceStore(), props);
        timed.setJournal(journal);
        String id = caseId(jdbc, "timeout");
        var interrupted = new CountDownLatch(1);
        try (var ignored = CaseExecution.open(id)) {
            var run = timed.run("synthetic", plan(), (node, inputs) -> {
                if (node.equals("credit-risk")) {
                    try { Thread.sleep(Duration.ofMinutes(1)); }
                    catch (InterruptedException e) { interrupted.countDown(); throw e; }
                }
                return node;
            }, new FlowRuntime.Options<>(null, (node, error, ms) -> "UNAVAILABLE", null));
            check(run.result("credit-risk").status() == FlowRuntime.Status.TIMEOUT && run.value("compliance") != null,
                    "Deadline/degradation behavior changed");
            check(interrupted.await(5, TimeUnit.SECONDS), "Timed-out agent not interrupted");
            check(journal.runs(id, 1).getFirst().status().equals("UNCERTAIN"), "Timeout was certified complete");
            fails(() -> journal.begin(plan(), id), "reconciliation");
        } finally {
            timed.shutdown();
        }
    }

    private static void verifyProcessCrash(Path directory) throws Exception {
        Path db = directory.resolve("crashed").toAbsolutePath();
        Path log = directory.resolve("crash.log");
        Process worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                FlowExecutionJournalProbe.class.getName(), "worker", db.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        String id;
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!Files.exists(log) || !Files.readString(log).contains("CHECKPOINT=")) {
                check(worker.isAlive() && System.nanoTime() < deadline, "Crash checkpoint unavailable");
                Thread.sleep(25);
            }
            id = Files.readAllLines(log).stream().filter(s -> s.startsWith("CHECKPOINT=")).findFirst()
                    .orElseThrow().substring("CHECKPOINT=".length());
        } finally {
            worker.destroyForcibly();
            check(worker.waitFor(10, TimeUnit.SECONDS), "Killed journal worker did not terminate");
        }
        JdbcTemplate jdbc = database(db);
        var journal = journal(jdbc);
        var run = journal.runs(id, 1).getFirst();
        check(run.status().equals("RUNNING"), "Crash state was fabricated");
        var attempts = journal.attempts(run.runId());
        check(attempts.stream().anyMatch(a -> a.nodeId().equals("intake") && a.status().equals("OK")), "Committed progress lost");
        check(attempts.stream().anyMatch(a -> a.nodeId().equals("kyc") && a.status().equals("RUNNING")), "Interrupted attempt lost");
        fails(() -> journal.begin(plan(), id), "reconciliation");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void fails(Runnable work, String message) {
        try { work.run(); }
        catch (RuntimeException error) {
            check(error.getMessage().contains(message), "Wrong failure: " + error);
            return;
        }
        throw new AssertionError("Expected explicit failure: " + message);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("verify")) {
            verify(Path.of(args[1]));
            return;
        }
        JdbcTemplate jdbc = database(Path.of(args[1]));
        SchemaMigrator.migrate(jdbc);
        String id = caseId(jdbc, "crash");
        var journal = journal(jdbc);
        var runtime = runtime(journal);
        try (var ignored = CaseExecution.open(id)) {
            runtime.run("synthetic", plan(), (node, inputs) -> {
                if (node.equals("kyc")) {
                    System.out.println("CHECKPOINT=" + id);
                    System.out.flush();
                    Thread.sleep(Duration.ofMinutes(2));
                }
                return node;
            }, null);
        } finally {
            runtime.shutdown();
        }
    }
}
