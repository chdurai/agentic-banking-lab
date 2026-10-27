package io.github.chdurai.agenticbanking.flow;

import io.github.chdurai.agenticbanking.flow.plan.DagPlan;
import io.github.chdurai.agenticbanking.flow.plan.PlanResolver;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpec;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecOverride;
import io.github.chdurai.agenticbanking.flow.spec.AgentSpecRegistry;
import io.github.chdurai.agenticbanking.ops.ExecutionTraceStore;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** WI-35: spec validation, plan resolution and the DAG runtime, without Spring or a model. */
class FlowRuntimeTest {

    private final FlowProperties props = new FlowProperties();
    private final AgentSpecRegistry specs = AgentSpecRegistry.withDefaults();
    private final ExecutionTraceStore traces = new ExecutionTraceStore();
    private final List<PlatformEvent> events = new CopyOnWriteArrayList<>();
    private final PlanResolver resolver = new PlanResolver(specs, props,
            new PlatformEventPublisher(e -> events.add((PlatformEvent) e)));
    private FlowRuntime runtime = new FlowRuntime(specs, traces, props);

    @AfterEach
    void close() {
        runtime.shutdown();
    }

    // ---- specs -----------------------------------------------------------------------------

    @Test
    void defaultSpecsAreValid() {
        assertThat(specs.planned("loan")).extracting(AgentSpec::agentId)
                .containsExactly("intake", "kyc", "credit-risk", "compliance");
        assertThat(specs.reads("compliance")).containsExactly("intake", "kyc", "credit-risk");
    }

    @Test
    void cycleIsRejectedAtStartup() {
        List<AgentSpec> bad = new ArrayList<>(AgentSpecRegistry.defaults());
        bad.replaceAll(s -> s.agentId().equals("intake") ? s.toBuilder().reads("compliance").build() : s);
        assertThatThrownBy(() -> new AgentSpecRegistry(bad))
                .hasMessageContaining("reads cycle");
    }

    @Test
    void readOfUnknownAgentIsRejected() {
        assertThatThrownBy(() -> AgentSpecRegistry.withDefaults(
                Map.of("kyc", override(o -> o.setReads(List.of("intake", "ghost"))))))
                .hasMessageContaining("reads unknown agent 'ghost'");
    }

    @Test
    void overrideOfUnknownAgentIsRejected() {
        assertThatThrownBy(() -> AgentSpecRegistry.withDefaults(Map.of("nobody", override(o -> { }))))
                .hasMessageContaining("unknown agent 'nobody'");
    }

    @Test
    void requiredAgentCannotBeDropped() {
        List<AgentSpec> missing = AgentSpecRegistry.defaults().stream()
                .filter(s -> !s.agentId().equals("kyc"))
                .map(s -> s.toBuilder().reads(s.reads().stream().filter(r -> !r.equals("kyc")).toList()).build())
                .toList();
        assertThatThrownBy(() -> new AgentSpecRegistry(missing))
                .hasMessageContaining("missing required agent 'kyc'");
    }

    @Test
    void nonPositiveDeadlineIsRejected() {
        assertThatThrownBy(() -> AgentSpecRegistry.withDefaults(
                Map.of("kyc", override(o -> o.setDeadlineMs(0L)))))
                .hasMessageContaining("deadlineMs must be positive");
    }

    // ---- plans -----------------------------------------------------------------------------

    @Test
    void rulePlanRunsKycAndCreditRiskInParallelLayer() {
        DagPlan plan = resolver.rules("loan");
        assertThat(plan.layers()).containsExactly(List.of("intake"), List.of("kyc", "credit-risk"),
                List.of("compliance"));
        assertThat(plan.predecessors("compliance")).containsExactly("intake", "kyc", "credit-risk");
    }

    @Test
    void plannerCannotScheduleAReaderAlongsideItsSource() {
        DagPlan plan = resolver.resolve("loan", "c1",
                List.of(List.of("intake", "kyc", "credit-risk"), List.of("compliance")), "x", DagPlan.Source.LLM);
        assertThat(plan.fallback()).isTrue();
        assertThat(plan.fallbackReason()).contains("kyc reads intake but is scheduled alongside");
        assertThat(plan.source()).isEqualTo(DagPlan.Source.RULES);
        assertThat(events).extracting(PlatformEvent::type).contains("plan.fallback");
    }

    @Test
    void plannerCannotDropARequiredAgentOrInventOne() {
        assertThat(resolver.resolve("loan", "c2", List.of(List.of("intake"), List.of("compliance")), "x",
                DagPlan.Source.LLM).fallbackReason()).contains("plan must include kyc");
        assertThat(resolver.resolve("loan", "c3", List.of(List.of("intake"), List.of("time-travel")), "x",
                DagPlan.Source.LLM).fallbackReason()).contains("unknown agent");
        assertThat(resolver.resolve("loan", "c4", List.of(List.of("intake"), List.of("kyc", "kyc")), "x",
                DagPlan.Source.LLM).fallbackReason()).contains("duplicate");
    }

    @Test
    void nodeAndLlmCapsAreEnforced() {
        props.setMaxLlmCalls(2);
        DagPlan plan = resolver.resolve("loan", "c5", List.of(List.of("intake"), List.of("kyc", "credit-risk"),
                List.of("compliance")), "x", DagPlan.Source.LLM);
        assertThat(plan.fallbackReason()).contains("LLM calls");
    }

    @Test
    void validPlannerStagesAreKeptAsBarriers() {
        DagPlan plan = resolver.resolve("loan", "c6", List.of(List.of("intake"), List.of("kyc"),
                List.of("credit-risk"), List.of("compliance")), "kyc first", DagPlan.Source.LLM);
        assertThat(plan.fallback()).isFalse();
        assertThat(plan.layers()).containsExactly(List.of("intake"), List.of("kyc"), List.of("credit-risk"),
                List.of("compliance"));
    }

    // ---- runtime ---------------------------------------------------------------------------

    @Test
    void nodesReceiveTheirPredecessorsValuesAndSiblingsRunConcurrently() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        FlowRuntime.DagRun<String> run = runtime.run("case-1", resolver.rules("loan"), (id, in) -> {
            if (id.equals("kyc") || id.equals("credit-risk")) {
                bothStarted.countDown();
                // deadlocks (and times out) unless the siblings really overlap
                assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return id + "<" + String.join(",", in.keySet()) + ">";
        }, null);

        assertThat(run.stopped()).isFalse();
        assertThat(run.value("intake")).isEqualTo("intake<>");
        assertThat(run.value("compliance")).isEqualTo("compliance<intake,kyc,credit-risk>");
        assertThat(run.results()).extracting(FlowRuntime.NodeResult::agentId)
                .containsExactly("intake", "kyc", "credit-risk", "compliance");
        assertThat(traces.recent(10)).extracting(ExecutionTraceStore.TraceEntry::outcome).containsOnly("OK");
    }

    @Test
    void degradedNodeContinuesAndDependentsStillRun() {
        FlowRuntime.DagRun<String> run = runtime.run("case-2", resolver.rules("loan"), (id, in) -> {
            if (id.equals("credit-risk")) {
                throw new IllegalStateException("model down");
            }
            return id;
        }, new FlowRuntime.Options<>(null, (id, err, ms) -> "UNAVAILABLE:" + id, v -> v));

        assertThat(run.stopped()).isFalse();
        assertThat(run.value("credit-risk")).isEqualTo("UNAVAILABLE:credit-risk");
        assertThat(run.value("compliance")).isEqualTo("compliance");
        assertThat(run.firstFailure().agentId()).isEqualTo("credit-risk");
    }

    @Test
    void hardFailStopsNewNodesButKeepsInFlightSiblings() {
        FlowRuntime.DagRun<String> run = runtime.run("case-3", resolver.rules("loan"), (id, in) -> id,
                new FlowRuntime.Options<>(v -> v.equals("kyc"), null, null));

        assertThat(run.stopped()).isTrue();
        assertThat(run.value("kyc")).isEqualTo("kyc");
        assertThat(run.value("credit-risk")).isEqualTo("credit-risk");
        assertThat(run.result("compliance").status()).isEqualTo(FlowRuntime.Status.SKIPPED);
    }

    @Test
    void failureOfANonDegradableNodeStopsTheFlow() {
        DagPlan plan = resolver.rules("incident");
        FlowRuntime.DagRun<String> run = runtime.run("inc-1", plan, (id, in) -> {
            if (id.equals("incident-diagnosis")) {
                throw new IllegalStateException("rag down");
            }
            return id;
        }, null);

        assertThat(run.stopped()).isTrue();
        assertThat(run.firstFailure().error()).hasMessage("rag down");
        assertThat(run.result("incident-remediation").status()).isEqualTo(FlowRuntime.Status.SKIPPED);
        assertThat(traces.recent(10)).anySatisfy(t -> {
            assertThat(t.agent()).isEqualTo("incident-diagnosis");
            assertThat(t.outcome()).isEqualTo("ERROR: rag down");
        });
    }

    @Test
    void nodePastItsDeadlineTimesOutAndIsInterrupted() throws Exception {
        AgentSpecRegistry tight = AgentSpecRegistry.withDefaults(
                Map.of("credit-risk", override(o -> o.setDeadlineMs(150L))));
        runtime.shutdown();
        runtime = new FlowRuntime(tight, traces, props);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);

        FlowRuntime.DagRun<String> run = runtime.run("case-4", new PlanResolver(tight, props,
                new PlatformEventPublisher(e -> { })).rules("loan"), (id, in) -> {
            if (id.equals("credit-risk")) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    finished.countDown();
                    throw e;
                }
            }
            return id;
        }, new FlowRuntime.Options<>(null, (id, err, ms) -> "UNAVAILABLE:" + id, v -> v));

        assertThat(run.result("credit-risk").status()).isEqualTo(FlowRuntime.Status.TIMEOUT);
        assertThat(run.value("credit-risk")).isEqualTo("UNAVAILABLE:credit-risk");
        assertThat(run.value("compliance")).isEqualTo("compliance");
        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void dependentsOfAValuelessNodeAreSkipped() {
        FlowRuntime.DagRun<String> run = runtime.run("case-5", resolver.rules("grievance"), (id, in) -> {
            throw new IllegalStateException("down");
        }, null);
        assertThat(run.stopped()).isTrue();
        assertThat(run.result("grievance-response").status()).isEqualTo(FlowRuntime.Status.SKIPPED);
    }

    private static AgentSpecOverride override(java.util.function.Consumer<AgentSpecOverride> c) {
        AgentSpecOverride o = new AgentSpecOverride();
        c.accept(o);
        return o;
    }
}
