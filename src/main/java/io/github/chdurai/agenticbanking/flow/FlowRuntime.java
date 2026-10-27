package io.github.chdurai.agenticbanking.flow;

import jakarta.annotation.PreDestroy;
import io.github.chdurai.agenticbanking.flow.plan.DagPlan;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpec;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecRegistry;
import io.github.chdurai.agenticbanking.ops.ExecutionTraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The one DAG executor for every flow (v3.1 §3.5). A node starts as soon as every node it depends on has
 * finished, on its own virtual thread, under its spec's deadline. A node that fails is handled by its
 * spec's {@code onFailure}: DEGRADE substitutes a value (the flow's "unavailable" marker) and continues;
 * HOLD/ABORT stop scheduling new nodes. Work already in flight is always collected, never discarded.
 */
@Component
public class FlowRuntime {

    private static final Logger log = LoggerFactory.getLogger(FlowRuntime.class);

    public enum Status { OK, FAILED, TIMEOUT, SKIPPED }

    /** @param inputs values of this node's predecessors that produced one, in plan order */
    @FunctionalInterface
    public interface NodeExecutor<R> {
        R run(String agentId, Map<String, R> inputs) throws Exception;
    }

    @FunctionalInterface
    public interface Degrader<R> {
        R degrade(String agentId, Throwable error, long durationMs);
    }

    /**
     * @param stopWhen stop scheduling new nodes once a node's value matches (hard-fail fast); may be null
     * @param degrade  value for a failed or timed-out node; null means "no value" (dependents are skipped)
     * @param outcome  trace label for a node that produced a value; null means "OK"
     */
    public record Options<R>(Predicate<R> stopWhen, Degrader<R> degrade, Function<R, String> outcome) {
        public static <R> Options<R> defaults() {
            return new Options<>(null, null, null);
        }
    }

    public record NodeResult<R>(String agentId, Status status, R value, Throwable error, long durationMs) {
        public boolean hasValue() {
            return value != null;
        }
    }

    public record DagRun<R>(List<NodeResult<R>> results, boolean stopped, String stopReason) {
        public NodeResult<R> result(String agentId) {
            return results.stream().filter(r -> r.agentId().equals(agentId)).findFirst().orElse(null);
        }

        public R value(String agentId) {
            NodeResult<R> r = result(agentId);
            return r == null ? null : r.value();
        }

        /** First node, in plan order, that failed or timed out. */
        public NodeResult<R> firstFailure() {
            return results.stream()
                    .filter(r -> r.status() == Status.FAILED || r.status() == Status.TIMEOUT)
                    .findFirst().orElse(null);
        }

        /** Nodes that actually ran, in plan order. */
        public List<NodeResult<R>> executed() {
            return results.stream().filter(r -> r.status() != Status.SKIPPED).toList();
        }
    }

    private final AgentSpecRegistry specs;
    private final ExecutionTraceStore traces;
    private final FlowProperties props;
    private final io.github.chdurai.agenticbanking.ops.AgentActivityHub activity;
    private FlowExecutionJournal journal;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "flow-deadlines");
        t.setDaemon(true);
        return t;
    });

    public FlowRuntime(AgentSpecRegistry specs, ExecutionTraceStore traces, FlowProperties props) {
        this(specs, traces, props, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FlowRuntime(AgentSpecRegistry specs, ExecutionTraceStore traces, FlowProperties props,
                       @org.springframework.lang.Nullable io.github.chdurai.agenticbanking.ops.AgentActivityHub activity) {
        this.specs = specs;
        this.traces = traces;
        this.props = props;
        this.activity = activity;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        timers.shutdownNow();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setJournal(FlowExecutionJournal journal) {
        this.journal = journal;
    }

    public <R> DagRun<R> run(String caseId, DagPlan plan, NodeExecutor<R> exec, Options<R> options) {
        FlowExecutionJournal.Owner owner = journal == null ? null : journal.begin(plan, io.github.chdurai.agenticbanking.cases.CaseExecution.current());
        try {
            DagRun<R> result = execute(caseId, plan, exec, options, owner);
            if (owner != null) journal.finished(owner, result.firstFailure() != null ? "UNCERTAIN"
                    : result.stopped() ? "STOPPED" : "COMPLETED");
            return result;
        } catch (RuntimeException | Error failure) {
            if (owner != null) {
                try {
                    journal.finished(owner, Thread.currentThread().isInterrupted() ? "CANCELLED" : "UNCERTAIN");
                } catch (RuntimeException persistenceFailure) {
                    failure.addSuppressed(persistenceFailure);
                    log.error("Execution {} outcome is uncertain; journal could not close it", owner.runId(), persistenceFailure);
                }
            }
            throw failure;
        }
    }

    private <R> DagRun<R> execute(String caseId, DagPlan plan, NodeExecutor<R> exec, Options<R> options,
                                  FlowExecutionJournal.Owner owner) {
        Options<R> opts = options == null ? Options.defaults() : options;
        Map<String, NodeResult<R>> done = new LinkedHashMap<>();
        Map<String, CompletableFuture<NodeResult<R>>> running = new LinkedHashMap<>();
        String stopReason = null;

        try {
            while (done.size() < plan.nodes().size()) {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Flow cancelled");
                for (String node : plan.nodes()) {
                    if (done.containsKey(node) || running.containsKey(node)) {
                        continue;
                    }
                    List<String> preds = plan.predecessors(node);
                    if (stopReason != null) {
                        done.put(node, skipped(node));
                        continue;
                    }
                    if (!preds.stream().allMatch(done::containsKey)) {
                        continue;
                    }
                    Map<String, R> inputs = new LinkedHashMap<>();
                    for (String p : preds) {
                        R v = done.get(p).value();
                        if (v != null) {
                            inputs.put(p, v);
                        }
                    }
                    if (inputs.size() < preds.size()) {
                        done.put(node, skipped(node));
                        continue;
                    }
                    if (activity != null) {
                        activity.agentStarted(plan.flow(), caseId, node);
                    }
                    if (owner != null) journal.started(owner, node, preds);
                    running.put(node, start(node, inputs, exec, opts));
                }
                if (running.isEmpty()) {
                    if (done.size() < plan.nodes().size()) {
                        // Unreachable with a validated acyclic plan; never spin.
                        for (String node : plan.nodes()) {
                            done.putIfAbsent(node, skipped(node));
                        }
                    }
                    break;
                }
                try {
                    CompletableFuture.anyOf(running.values().toArray(CompletableFuture[]::new)).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException("Flow cancelled while awaiting agent results");
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException("Agent scheduling failed", e.getCause());
                }
                for (var it = running.entrySet().iterator(); it.hasNext(); ) {
                    var entry = it.next();
                    if (!entry.getValue().isDone()) {
                        continue;
                    }
                    it.remove();
                    NodeResult<R> r = entry.getValue().join();
                    if (owner != null) journal.completed(owner, r);
                    done.put(entry.getKey(), r);
                    trace(plan.flow(), caseId, r, opts);
                    if (stopReason == null) {
                        stopReason = stopReason(r, opts);
                        if (stopReason != null) {
                            log.warn("[{}] {} stops further scheduling: {}", plan.flow(), r.agentId(), stopReason);
                        }
                    }
                }
            }

            List<NodeResult<R>> ordered = new ArrayList<>();
            for (String node : plan.nodes()) {
                ordered.add(done.get(node));
            }
            return new DagRun<>(List.copyOf(ordered), stopReason != null, stopReason);
        } finally {
            running.values().forEach(future -> future.cancel(true));
        }
    }

    private <R> String stopReason(NodeResult<R> r, Options<R> opts) {
        if (r.value() != null && opts.stopWhen() != null && opts.stopWhen().test(r.value())) {
            return r.agentId() + " hard-failed";
        }
        if (r.status() == Status.FAILED || r.status() == Status.TIMEOUT) {
            AgentSpec spec = specs.find(r.agentId()).orElse(null);
            if (spec != null && spec.onFailure() != AgentSpec.OnFailure.DEGRADE) {
                return r.agentId() + " " + r.status().name().toLowerCase() + " (onFailure=" + spec.onFailure() + ")";
            }
        }
        return null;
    }

    private <R> CompletableFuture<NodeResult<R>> start(String node, Map<String, R> inputs,
                                                       NodeExecutor<R> exec, Options<R> opts) {
        AgentSpec spec = specs.find(node).orElse(null);
        long deadline = deadlineMs(spec);
        long startNanos = System.nanoTime();
        CompletableFuture<NodeResult<R>> result = new CompletableFuture<>();
        String durableCaseId = io.github.chdurai.agenticbanking.cases.CaseExecution.current();
        Future<?> task = executor.submit(io.github.chdurai.agenticbanking.security.CallerContext.wrap(() -> {
            NodeResult<R> r;
            try (var caseScope = io.github.chdurai.agenticbanking.cases.CaseExecution.open(durableCaseId)) {
                R value = exec.run(node, inputs);
                r = new NodeResult<>(node, Status.OK, value, null, elapsed(startNanos));
            } catch (Throwable t) {
                r = degraded(node, Status.FAILED, t, startNanos, opts);
            }
            result.complete(r);
        }));
        ScheduledFuture<?> timer = timers.schedule(() -> {
            if (!result.isDone()) {
                result.complete(degraded(node, Status.TIMEOUT,
                        new TimeoutException("node deadline " + deadline + "ms exceeded"), startNanos, opts));
            }
        }, deadline, TimeUnit.MILLISECONDS);
        result.whenComplete((r, e) -> {
            timer.cancel(false);
            if (result.isCancelled() || r != null && r.status() == Status.TIMEOUT) {
                task.cancel(true);
            }
        });
        return result;
    }

    private <R> NodeResult<R> degraded(String node, Status status, Throwable error, long startNanos,
                                       Options<R> opts) {
        long ms = elapsed(startNanos);
        R value = null;
        if (opts.degrade() != null) {
            try {
                value = opts.degrade().degrade(node, error, ms);
            } catch (RuntimeException e) {
                log.warn("[{}] degrade handler failed: {}", node, e.getMessage());
            }
        }
        return new NodeResult<>(node, status, value, error, ms);
    }

    private long deadlineMs(AgentSpec spec) {
        long node = spec == null ? AgentSpec.DEFAULT_DEADLINE_MS : spec.deadlineMs();
        long cap = props.getPerNodeDeadlineMs();
        return cap > 0 ? Math.min(node, cap) : node;
    }

    private static long elapsed(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    private static <R> NodeResult<R> skipped(String node) {
        return new NodeResult<>(node, Status.SKIPPED, null, null, 0);
    }

    private <R> void trace(String flow, String caseId, NodeResult<R> r, Options<R> opts) {
        String outcome;
        if (r.value() != null) {
            outcome = opts.outcome() == null ? "OK" : opts.outcome().apply(r.value());
        } else if (r.status() == Status.TIMEOUT) {
            outcome = "TIMEOUT";
        } else {
            outcome = "ERROR: " + (r.error() == null ? "unknown" : r.error().getMessage());
        }
        traces.record(flow, caseId, r.agentId(), r.durationMs(), outcome);
        if (activity != null && r.status() != Status.SKIPPED) {
            activity.agentCompleted(flow, caseId, r.agentId(), r.durationMs(), outcome,
                    r.status() == Status.OK ? "OK" : r.status().name());
        }
    }
}
