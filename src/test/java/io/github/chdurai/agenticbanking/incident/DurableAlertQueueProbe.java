package io.github.chdurai.agenticbanking.incident;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.chdurai.agenticbanking.cases.SchemaMigrator;
import io.github.chdurai.agenticbanking.security.CallerContext;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class DurableAlertQueueProbe {
    private DurableAlertQueueProbe() {}

    private static JdbcTemplate database(Path path) {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + path.toAbsolutePath());
        return new JdbcTemplate(ds);
    }

    private static JdbcAlertQueue queue(JdbcTemplate jdbc) {
        return new JdbcAlertQueue(jdbc, new TransactionTemplate(new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource()))), new ObjectMapper().findAndRegisterModules());
    }

    private static AlertSignal signal(String title, String environment) {
        return new AlertSignal(title, "Synthetic alert fixture", "loan-service", environment, "synthetic",
                "db_pool", List.of("test-only"), Instant.now());
    }

    public static void verify(Path directory) throws Exception {
        Files.createDirectories(directory);
        JdbcTemplate jdbc = database(directory.resolve("alerts"));
        SchemaMigrator.migrate(jdbc);
        var queue = queue(jdbc);
        var first = queue.enqueue(signal("capacity", "staging"), 1);
        try {
            queue.enqueue(signal("new rejected alert", "staging"), 1);
            throw new AssertionError("Overload was accepted");
        } catch (ResponseStatusException rejected) {
            check(rejected.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS, "Wrong overload status");
        }
        var restored = queue(jdbc);
        check(restored.find(first.alertId()).orElseThrow().status().equals("PENDING"), "Accepted alert lost after restart");
        var claim = restored.claim(10).orElseThrow();
        check(claim.alertId().equals(first.alertId()) && !claim.correlated(), "Wrong first alert claimed");
        fails(() -> restored.completed(new JdbcAlertQueue.Claim(claim.alertId(), claim.tenantId(), "stale",
                claim.signal(), false)), "stale");
        restored.completed(claim);
        var duplicate = queue.enqueue(signal("capacity", "staging"), 10);
        var correlated = queue(jdbc).claim(10).orElseThrow();
        check(correlated.alertId().equals(duplicate.alertId()) && correlated.correlated(), "Restart lost correlation");
        check(queue.find(duplicate.alertId()).orElseThrow().status().equals("CORRELATED"), "Correlation not durable");
        queue.enqueue(signal("capacity", "production"), 10);
        var differentEnvironment = queue.claim(10).orElseThrow();
        check(!differentEnvironment.correlated(), "Production suppressed by staging");
        queue.uncertain(differentEnvironment);
        queue.enqueue(signal("capacity", "production"), 10);
        queue.enqueue(signal("independent", "production"), 10);
        var independent = queue.claim(10).orElseThrow();
        check(independent.signal().title().equals("independent"), "Uncertain duplicate blocked independent work");
        queue.completed(independent);
        check(queue.claim(10).isEmpty(), "Uncertain fingerprint was blindly retried");
        try (var ignored = new CallerContext("other", "officer", Set.of("PLATFORM_ADMIN")).open()) {
            check(queue.find(first.alertId()).isEmpty(), "Cross-tenant receipt disclosure");
            fails(() -> queue.completed(claim), "tenant mismatch");
            queue.enqueue(signal("capacity", "staging"), 1);
            var other = queue.claim(10).orElseThrow();
            check(!other.correlated(), "Correlation crossed tenants");
            queue.completed(other);
        }
        try (var ignored = new CallerContext("concurrent", "officer", Set.of("PLATFORM_ADMIN")).open();
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = pool.invokeAll(java.util.stream.IntStream.range(0, 12)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> CallerContext.wrapCall(() -> {
                        try { queue.enqueue(signal("event-" + i, "staging"), 1); return true; }
                        catch (ResponseStatusException rejected) {
                            if (rejected.getStatusCode() != HttpStatus.TOO_MANY_REQUESTS) throw rejected;
                            return false;
                        }
                    })).toList());
            int accepted = 0;
            for (var result : results) if (result.get()) accepted++;
            check(accepted == 1, "Concurrent capacity check overaccepted: " + accepted);
        }
        verifyKilledWorker(directory);
        System.out.println("PASS alerts: durable admission, explicit 429/no eviction, restarted correlation, environment/tenant isolation, "
                + "stale-owner refusal, uncertain fingerprint isolation, concurrent capacity, killed-worker receipt");
    }

    private static void verifyKilledWorker(Path directory) throws Exception {
        Path db = directory.resolve("crash-alerts");
        Path log = directory.resolve("worker.log");
        Process worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                DurableAlertQueueProbe.class.getName(), "worker", db.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        String id;
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!Files.exists(log) || !Files.readString(log).contains("CLAIMED=")) {
                check(worker.isAlive() && System.nanoTime() < deadline, "Accepted alert checkpoint unavailable");
                Thread.sleep(25);
            }
            id = Files.readAllLines(log).stream().filter(line -> line.startsWith("CLAIMED="))
                    .findFirst().orElseThrow().substring("CLAIMED=".length());
        } finally {
            worker.destroyForcibly();
            check(worker.waitFor(10, TimeUnit.SECONDS), "Killed alert worker did not terminate");
        }
        var restored = queue(database(db));
        check(restored.find(id).orElseThrow().status().equals("RUNNING"), "Acknowledged receipt vanished after kill");
        check(restored.claim(10).isEmpty(), "Killed worker caused blind replay");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void fails(Runnable task, String message) {
        try { task.run(); }
        catch (IllegalStateException failure) {
            check(failure.getMessage().contains(message), "Unexpected failure: " + failure);
            return;
        }
        throw new AssertionError("Expected refusal: " + message);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("verify")) {
            verify(Path.of(args[1]));
            return;
        }
        JdbcTemplate jdbc = database(Path.of(args[1]));
        SchemaMigrator.migrate(jdbc);
        var queue = queue(jdbc);
        queue.enqueue(signal("worker crash", "staging"), 1);
        var claim = queue.claim(10).orElseThrow();
        System.out.println("CLAIMED=" + claim.alertId());
        System.out.flush();
        Thread.sleep(Duration.ofMinutes(2));
    }
}
