package io.github.chdurai.agenticbanking.approval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.chdurai.agenticbanking.cases.SchemaMigrator;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
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
import java.util.concurrent.TimeUnit;

/** Also runnable without a test engine when only the application's packaged dependencies are available. */
public final class LoanRecoveryProcessProbe {
    private LoanRecoveryProcessProbe() {}

    static ApprovalQueue queue(JdbcTemplate jdbc, ApprovalActionHandler handler) {
        var props = new HitlProperties();
        props.setEnforceRoles(true);
        return new ApprovalQueue(new AgentMetrics(new SimpleMeterRegistry()), null, new JdbcApprovalStore(jdbc),
                List.of(handler), List.of(), props);
    }

    static ApprovalEvidencePacket packet() {
        return new ApprovalEvidencePacket(1, "loan", "SYNTHETIC-LOAN",
                List.of("Synthetic fixture; not verified customer evidence"),
                List.of(new ApprovalEvidencePacket.AgentFinding("credit-risk", "WARN", 65,
                        "Synthetic risk requires underwriter review", List.of("fixture:credit-v1"))),
                List.of("policyVersion=synthetic-loan-v1"), List.of("human approval required"), "fixture critique",
                List.of("policy:synthetic-loan-v1", "fixture:credit-v1"),
                new ApprovalEvidencePacket.SideEffectPreview(List.of("SIMULATED receipt only"), List.of("No effect")),
                List.of("Synthetic sources"), "MANUAL_REVIEW", 65, true);
    }

    static ApprovalRequest submit(ApprovalQueue queue) {
        return queue.submit("loan", "SYNTHETIC-LOAN", "Synthetic recovery review", List.of("Fixture only"),
                packet(), SimulatedLoanBooking.action(), "requester", null);
    }

    static ReviewerIdentity reviewer() {
        return new ReviewerIdentity("underwriter", Set.of(Roles.LOAN_UNDERWRITER));
    }

    public static void verify(Path directory) throws Exception {
        for (String crash : List.of("before-effect", "after-effect")) {
            Path scenario = Files.createDirectories(directory.resolve(crash));
            Path output = scenario.resolve("prepare.log");
            Process before = start(scenario, "prepare", "", output);
            String id;
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (!Files.exists(output) || !Files.readString(output).contains("CHECKPOINT=")) {
                    check(before.isAlive() && System.nanoTime() < deadline, "Checkpoint unavailable: " + Files.readString(output));
                    Thread.sleep(25);
                }
                id = Files.readAllLines(output).stream().filter(line -> line.startsWith("CHECKPOINT="))
                        .findFirst().orElseThrow().substring("CHECKPOINT=".length());
            } finally {
                before.destroyForcibly();
                check(before.waitFor(10, TimeUnit.SECONDS), "Killed worker did not terminate");
            }
            JdbcTemplate jdbc = database(scenario.resolve("ledger").toString());
            check(new JdbcApprovalStore(jdbc).find(id).evidencePacket().equals(packet()), "Pending evidence lost on kill");
            run(scenario, crash, id, 23);
            check(new JdbcApprovalStore(jdbc).find(id).executionLog().isEmpty(), "Crash must precede outcome persistence");
            check(count(jdbc) == (crash.equals("after-effect") ? 1 : 0), "Unexpected pre-recovery effect count");
            run(scenario, "reconcile", id, 0);
            run(scenario, "reconcile", id, 0);
            var store = new JdbcApprovalStore(jdbc);
            var request = store.find(id);
            check(request.evidencePacket().equals(packet()), "Frozen findings changed across restart");
            check(request.status() == ApprovalRequest.Status.APPROVED, "Approval not retained");
            check(request.executionLog().size() == 4, "Receipt not persisted");
            check(count(jdbc) == 1, "Duplicate simulated effect");
            check(store.requireSeal(request).sha256().equals(jdbc.queryForObject(
                    "SELECT evidence_sha256 FROM simulated_loan_receipt", String.class)), "Receipt evidence mismatch");
            var booking = booking(jdbc);
            try (var ignored = new CallerContext("other", "officer", Set.of(Roles.PLATFORM_ADMIN)).open()) {
                check(store.find(id) == null, "Cross-tenant approval disclosure");
                expectFailure(() -> booking.execute(request), "unavailable");
            }
            try (var ignored = new CallerContext("dev", "officer", Set.of(Roles.GRM_OFFICER)).open()) {
                expectFailure(() -> queue(jdbc, booking).reconcile(id), "Flow role");
            }
            jdbc.update("UPDATE approval_evidence_seal SET sha256=?", "0".repeat(64));
            expectFailure(() -> store.find(id), "integrity");
            check(count(jdbc) == 1, "Integrity failure changed effect count");
            System.out.println("PASS " + crash + ": killed JVM, frozen findings, approved checkpoint, one receipt, "
                    + "duplicate reconciliation, tenant/role denial, corruption refusal");
        }
        verifyContracts(directory.resolve("contracts"));
    }

    private static void verifyContracts(Path path) throws Exception {
        JdbcTemplate jdbc = database(path.toString());
        SchemaMigrator.migrate(jdbc);
        var store = new JdbcApprovalStore(jdbc);
        var booking = booking(jdbc);
        var queue = queue(jdbc, booking);
        jdbc.execute("ALTER TABLE approval_evidence_seal ADD CONSTRAINT reject_seal CHECK (schema_version=99)");
        try {
            submit(queue);
            throw new AssertionError("Expected seal insertion failure");
        } catch (org.springframework.dao.DataAccessException expected) {
            check(jdbc.queryForObject("SELECT COUNT(*) FROM approvals", Integer.class) == 0,
                    "Approval insertion did not roll back with failed seal");
        }
        jdbc.execute("ALTER TABLE approval_evidence_seal DROP CONSTRAINT reject_seal");
        var pending = submit(queue);
        expectFailure(() -> booking.execute(pending), "original approved");
        var approved = pending.decided(ApprovalRequest.Status.APPROVED, "underwriter", "REVIEWED", "ok", java.time.Instant.now());
        check(store.replace(pending, approved), "Could not prepare approved checkpoint");
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var results = pool.invokeAll(java.util.stream.IntStream.range(0, 12)
                    .<java.util.concurrent.Callable<List<String>>>mapToObj(i -> () -> queue.reconcile(pending.id()).executionLog())
                    .toList());
            var log = results.getFirst().get();
            check(log.size() == 4, "Missing concurrent execution result");
            for (var result : results) check(result.get().equals(log), "Concurrent reconciliation changed the receipt");
        }
        check(count(jdbc) == 1, "Concurrent reconciliation duplicated the effect");
        var missing = submit(queue);
        jdbc.update("DELETE FROM approval_evidence_seal WHERE approval_id=?", missing.id());
        var missingResult = queue.handle(missing.id(), new ApprovalCommand.Approve("REVIEWED", "ok"), reviewer());
        check(missingResult.status() == ApprovalRequest.Status.EXECUTION_FAILED
                && missingResult.executionLog().getFirst().contains("Frozen evidence is missing"), "Missing seal accepted");
        var incomplete = queue.submit("loan", "incomplete", "Incomplete fixture", List.of(), SimulatedLoanBooking.action());
        var incompleteResult = queue.handle(incomplete.id(), new ApprovalCommand.Approve("REVIEWED", "ok"), reviewer());
        check(incompleteResult.status() == ApprovalRequest.Status.EXECUTION_FAILED
                && incompleteResult.executionLog().getFirst().contains("Incomplete"), "Incomplete evidence accepted");
        var rejected = submit(queue);
        queue.handle(rejected.id(), new ApprovalCommand.Reject("REJECTED", "no"), reviewer());
        expectFailure(() -> queue.reconcile(rejected.id()), "no recoverable");
        ApprovalActionHandler ordinary = new ApprovalActionHandler() {
            public String type() { return "ordinary"; }
            public List<String> execute(ApprovalRequest request) { return List.of("done"); }
        };
        var ordinaryQueue = queue(jdbc, ordinary);
        var ordinaryRequest = ordinaryQueue.submit("loan", "ordinary", "Ordinary", List.of(),
                new ActionSpec("ordinary", java.util.Map.of()));
        ordinaryQueue.handle(ordinaryRequest.id(), new ApprovalCommand.Approve("REVIEWED", "ok"), reviewer());
        expectFailure(() -> ordinaryQueue.reconcile(ordinaryRequest.id()), "no recoverable");
        var changed = new ApprovalRequest(approved.id(), approved.flow(), approved.subjectId(), approved.title(),
                approved.details(), approved.evidencePacket(), approved.status(), approved.createdAt(),
                approved.decidedAt(), approved.decidedBy(), approved.reasonCode(), approved.note(), approved.executionLog(),
                approved.requestedBy(), Roles.PLATFORM_ADMIN, approved.expiresAt(), approved.escalatedAt(),
                approved.blindFirstRequired(), approved.blindFirstAssessment(), approved.reasonCodeRequired(),
                approved.action(), approved.version() + 1);
        expectFailure(() -> store.replace(approved, changed), "cannot change");
        check(count(jdbc) == 1, "Negative controls changed the effect count");
        System.out.println("PASS contracts: atomic seal rollback, 12 concurrent reconciliations/one receipt, "
                + "pending/missing/incomplete/rejected/ordinary-action refusal, frozen authority");
    }

    private static int count(JdbcTemplate jdbc) {
        return java.util.Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM simulated_loan_receipt", Integer.class));
    }

    private static void expectFailure(Runnable task, String message) {
        try {
            task.run();
        } catch (IllegalStateException | IllegalArgumentException e) {
            check(e.getMessage().contains(message), "Wrong refusal: " + e);
            return;
        } catch (ApprovalDeniedException e) {
            check(e.getMessage().contains(message), "Wrong authorization refusal: " + e);
            return;
        }
        throw new AssertionError("Expected refusal: " + message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Process start(Path directory, String mode, String id, Path output) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                LoanRecoveryProcessProbe.class.getName(), "worker", directory.resolve("ledger").toString(), mode, id)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    }

    private static void run(Path directory, String mode, String id, int exit) throws Exception {
        Path output = directory.resolve(mode + "-" + System.nanoTime() + ".log");
        Process process = start(directory, mode, id, output);
        try {
            check(process.waitFor(30, TimeUnit.SECONDS), "Worker did not terminate");
            check(process.exitValue() == exit, "Worker failed: " + Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    private static JdbcTemplate database(String path) {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + path);
        return new JdbcTemplate(ds);
    }

    private static SimulatedLoanBooking booking(JdbcTemplate jdbc) {
        return new SimulatedLoanBooking(jdbc, new TransactionTemplate(
                new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource()))));
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("verify")) {
            verify(Path.of(args[1]));
            return;
        }
        JdbcTemplate jdbc = database(args[1]);
        SchemaMigrator.migrate(jdbc);
        var booking = new SimulatedLoanBooking(jdbc, new TransactionTemplate(
                new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())))) {
            @Override public List<String> execute(ApprovalRequest request) {
                if (args[2].equals("before-effect")) Runtime.getRuntime().halt(23);
                var receipt = super.execute(request);
                if (args[2].equals("after-effect")) Runtime.getRuntime().halt(23);
                return receipt;
            }
        };
        var queue = queue(jdbc, booking);
        switch (args[2]) {
            case "prepare" -> {
                var request = submit(queue);
                System.out.println("CHECKPOINT=" + request.id());
                System.out.flush();
                Thread.sleep(Duration.ofMinutes(2));
            }
            case "before-effect", "after-effect" -> queue.handle(args[3],
                    new ApprovalCommand.Approve("SYNTHETIC_REVIEW", "Fixture only"), reviewer());
            case "reconcile" -> System.out.println(queue.reconcile(args[3]).executionLog());
            default -> throw new IllegalArgumentException("Unknown worker mode");
        }
    }
}
