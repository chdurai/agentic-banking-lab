package io.github.chdurai.agenticbanking.llm;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.chdurai.agenticbanking.config.AgentProperties;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.ResourceAccessException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests of the gateway's resilience behaviour; no Spring context, no network. */
class LlmGatewayTest {

    record Out(String answer) {}

    static final String GOOD = "{\"answer\":\"ok\"}";

    /** Model whose behaviour is a function of (model name, call number). */
    static class FakeModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> modelsSeen = new ArrayList<>();
        final List<String> users = new ArrayList<>();
        volatile Function<Integer, String> behaviour = n -> GOOD;
        volatile Map<String, RuntimeException> failByModel = Map.of();
        volatile boolean reportUsage = true;
        volatile org.springframework.ai.chat.metadata.Usage usage = new org.springframework.ai.chat.metadata.DefaultUsage(10, 5);

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            int n = calls.incrementAndGet();
            String model = prompt.getOptions() == null ? "?" : prompt.getOptions().getModel();
            modelsSeen.add(model);
            users.add(prompt.getUserMessage().getText());
            RuntimeException fail = failByModel.get(model);
            if (fail != null) {
                throw fail;
            }
            var generations = List.of(new Generation(new AssistantMessage(behaviour.apply(n))));
            return reportUsage ? new ChatResponse(generations, org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                    .usage(usage).build()) : new ChatResponse(generations);
        }
    }

    static class Harness {
        final FakeModel model = new FakeModel();
        final List<PlatformEvent> events = new ArrayList<>();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final AgentProperties agents = new AgentProperties();
        LlmProperties props;
        LlmGateway gateway;

        Harness(String primary, String fallback, java.util.function.UnaryOperator<LlmProperties> tune) {
            agents.getModels().put("a", primary);
            agents.setFallbackModel(fallback);
            LlmProperties p = LlmProperties.defaults().withAllowSameFallback(true).withResilience(
                    LlmProperties.Resilience.defaults()
                            .withRetry(new LlmProperties.Retry(2, 1, 1.0, 0.0))
                            .withTimeoutMs(2_000));
            props = tune.apply(p);
            PlatformEventPublisher publisher = new PlatformEventPublisher(new ApplicationEventPublisher() {
                @Override
                public void publishEvent(Object event) {
                    if (event instanceof PlatformEvent e) {
                        events.add(e);
                    }
                }
            });
            gateway = new LlmGateway(() -> ChatClient.builder(model), agents, props,
                    new AgentMetrics(registry, agents), publisher, new PromptRegistry(),
                    new LocalTokenQuota(props), List.of(), registry);
        }

        LlmResult<Out> call() {
            return gateway.call(AgentCallSpec.of("a", "You are a.", "hello", Out.class, 0.0));
        }
    }

    private static Harness same() {
        return new Harness("m1", "m1", p -> p);
    }

    private static Harness withFallback() {
        return new Harness("m1", "m2", p -> p);
    }

    @Test
    void returnsValueWithProvenance() {
        LlmResult<Out> r = same().call();
        assertThat(r.value().answer()).isEqualTo("ok");
        assertThat(r.model()).isEqualTo("m1");
        assertThat(r.promptVersion()).isEqualTo("v1");
        assertThat(r.promptHash()).hasSize(12);
        assertThat(r.fallbackUsed()).isFalse();
    }

    @Test
    void transientErrorsAreRetriedOnTheSameModel() {
        Harness h = same();
        h.model.behaviour = n -> {
            if (n < 3) {
                throw new TransientAiException("429 too many requests");
            }
            return GOOD;
        };
        LlmResult<Out> r = h.call();
        assertThat(r.value().answer()).isEqualTo("ok");
        assertThat(h.model.calls.get()).isEqualTo(3);
        assertThat(r.providerCalls()).isEqualTo(3);
    }

    @Test
    void nonTransientAndAgentErrorsAreNotRetried() {
        Harness h = same();
        h.model.behaviour = n -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PROVIDER));
        assertThat(h.model.calls.get()).isEqualTo(1);

        Harness h2 = same();
        h2.model.behaviour = n -> {
            throw new NonTransientAiException("400 bad request");
        };
        assertThatThrownBy(h2::call).isInstanceOf(LlmException.class);
        assertThat(h2.model.calls.get()).isEqualTo(1);
    }

    @Test
    void retriesStopAfterConfiguredMaximum() {
        Harness h = same();
        h.model.behaviour = n -> {
            throw new ResourceAccessException("connection reset");
        };
        assertThatThrownBy(h::call).isInstanceOf(LlmException.class);
        assertThat(h.model.calls.get()).isEqualTo(3);
    }

    @Test
    void fallsBackToSecondModelWhenPrimaryIsDown() {
        Harness h = withFallback();
        h.model.failByModel = Map.of("m1", new TransientAiException("503 unavailable"));
        LlmResult<Out> r = h.call();
        assertThat(r.model()).isEqualTo("m2");
        assertThat(r.fallbackUsed()).isTrue();
        assertThat(h.model.modelsSeen).containsExactly("m1", "m1", "m1", "m2");
        assertThat(h.events).anyMatch(e -> e.type().equals("llm.fallback.used"));
    }

    @Test
    void noSecondAttemptWhenFallbackEqualsPrimary() {
        Harness h = same();
        h.model.behaviour = n -> {
            throw new IllegalStateException("x");
        };
        assertThatThrownBy(h::call).isInstanceOf(LlmException.class);
        assertThat(h.model.calls.get()).isEqualTo(1);
    }

    @Test
    void breakerOpensOnProviderFailuresAndShortCircuits() {
        Harness h = new Harness("m1", "m1", p -> p.withResilience(p.resilience()
                .withRetry(new LlmProperties.Retry(0, 1, 1.0, 0.0))
                .withBreaker(new LlmProperties.Breaker(50, 4, 4, 60_000, 1))));
        h.model.behaviour = n -> {
            throw new TransientAiException("503");
        };
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(h::call).isInstanceOf(LlmException.class);
        }
        int before = h.model.calls.get();
        assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BREAKER_OPEN));
        assertThat(h.model.calls.get()).isEqualTo(before);
        assertThat(h.events).anyMatch(e -> e.type().equals("llm.breaker.opened"));
        assertThat(h.registry.find("llm.breaker.state").gauge().value()).isEqualTo(2.0);
        assertThat(h.gateway.snapshot().toString()).contains("OPEN");
    }

    @Test
    void agentSpecificFailuresNeverOpenTheBreaker() {
        Harness h = new Harness("m1", "m1", p -> p.withResilience(p.resilience()
                .withBreaker(new LlmProperties.Breaker(50, 4, 4, 60_000, 1))));
        h.model.behaviour = n -> {
            throw new IllegalStateException("bad prompt for one agent");
        };
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                    e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PROVIDER));
        }
        assertThat(h.model.calls.get()).isEqualTo(10);
    }

    @Test
    void slowModelTimesOutWithoutBlockingTheCaller() {
        Harness h = new Harness("m1", "m1", p -> p.withResilience(p.resilience().withTimeoutMs(150)));
        h.model.behaviour = n -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GOOD;
        };
        long start = System.nanoTime();
        assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.TIMEOUT));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_500);
    }

    @Test
    void bulkheadRejectsWhenSaturated() throws Exception {
        Harness h = new Harness("m1", "m1", p -> p.withResilience(p.resilience()
                .withBulkhead(new LlmProperties.Bulkhead(1, 0))));
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        h.model.behaviour = n -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GOOD;
        };
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<LlmResult<Out>> first = pool.submit(h::call);
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                    e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BULKHEAD_FULL));
            release.countDown();
            assertThat(first.get().value().answer()).isEqualTo("ok");
        }
    }

    @Test
    void killSwitchBlocksCallsAndIsToggleable() {
        Harness h = same();
        h.gateway.setKillSwitch(true);
        assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.KILL_SWITCH));
        assertThat(h.model.calls.get()).isZero();
        h.gateway.setKillSwitch(false);
        assertThat(h.call().value().answer()).isEqualTo("ok");
        assertThat(h.events).filteredOn(e -> e.type().equals("llm.killswitch")).hasSize(2);
    }

    @Test
    void invalidOutputIsRepairedOnceWithTheValidationError() {
        Harness h = same();
        h.model.behaviour = n -> n == 1 ? "this is not json at all" : GOOD;
        LlmResult<Out> r = h.call();
        assertThat(r.value().answer()).isEqualTo("ok");
        assertThat(h.model.calls.get()).isEqualTo(2);
        assertThat(h.model.users.get(1)).contains("previous reply was rejected");
    }

    @Test
    void persistentlyInvalidOutputFailsAsOutputInvalid() {
        Harness h = same();
        h.model.behaviour = n -> "nope";
        assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class, e -> {
            assertThat(e.kind()).isEqualTo(LlmException.Kind.OUTPUT_INVALID);
            assertThat(e.unavailable()).isFalse();
        });
        assertThat(h.model.calls.get()).isEqualTo(2);
    }

    @Test
    void emptyResponseIsAnErrorNotAValue() {
        Harness h = same();
        h.model.behaviour = n -> "  ";
        assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.EMPTY_RESPONSE));
    }

    @Test
    void exhaustedCaseBudgetBlocksFurtherCalls() {
        Harness h = same();
        CaseBudget budget = new CaseBudget(10, 0, 0);
        budget.charge(10, 0);
        assertThatThrownBy(() -> h.gateway.call(
                AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget).forCase("loan", "L1")))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BUDGET));
        assertThat(h.model.calls.get()).isZero();
        assertThat(h.events).anyMatch(e -> e.type().equals("llm.budget.exceeded"));
    }

    @Test
    void successfulCallsChargeTheBudget() {
        Harness h = same();
        CaseBudget budget = new CaseBudget(0, 0, 60_000);
        h.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget));
        assertThat(budget.exhausted()).isNull();
        assertThat(budget.remainingMillis()).isLessThanOrEqualTo(60_000);
    }

    @Test
    void quotaExhaustionIsRejectedBeforeAnyProviderCall() {
        Harness h = new Harness("m1", "m1", p -> p.withResilience(p.resilience()
                .withQuota(new LlmProperties.Quota(4_000))));
        h.call();
        assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.QUOTA));
        assertThat(h.model.calls.get()).isEqualTo(1);
    }

    @Test
    void routeOverridesTakePrecedenceOverLegacyKeys() {
        Harness h = new Harness("m1", "m1", p -> p.withRoutes(Map.of("a",
                new LlmProperties.Route("p2", "f2", 0.5, 100, null))));
        assertThat(h.call().model()).isEqualTo("p2");
    }

    @Test
    void startupRejectsFallbackEqualToPrimaryUnlessAcknowledged() {
        assertThatThrownBy(() -> new Harness("m1", "m1", p -> p.withAllowSameFallback(false)).gateway.validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("fallback model equals primary");
        new Harness("m1", "m2", p -> p.withAllowSameFallback(false)).gateway.validate();
    }

    @Test
    void startupRejectsUnapprovedProviderAndMissingPromptVersion() {
        assertThatThrownBy(() -> new Harness("m1", "m2", p -> p.withProviders(
                new LlmProperties.Providers(List.of("azure-openai")))).gateway.validate())
                .hasMessageContaining("not in platform.llm.providers.approved");
        assertThatThrownBy(() -> new Harness("m1", "m2", p -> p.withRoutes(Map.of("a",
                new LlmProperties.Route(null, null, null, null, "v9")))).gateway.validate())
                .hasMessageContaining("prompt version 'v9' not found");
    }

    @Test
    void advisorsCanRewriteInputAndRejectOutput() {
        Harness h = same();
        LlmCallAdvisor advisor = new LlmCallAdvisor() {
            @Override
            public String beforeCall(String agentId, String system, String user) {
                return user + " [advised]";
            }

            @Override
            public void afterCall(String agentId, String modelText) {
                throw new LlmException(LlmException.Kind.OUTPUT_INVALID, "blocked by guardrail");
            }
        };
        LlmGateway g = new LlmGateway(() -> ChatClient.builder(h.model), h.agents, h.props,
                new AgentMetrics(h.registry, h.agents), null, new PromptRegistry(),
                new LocalTokenQuota(h.props), List.of(advisor), h.registry);
        assertThatThrownBy(() -> g.call(AgentCallSpec.of("a", "s", "u", Out.class, 0)))
                .hasMessageContaining("blocked by guardrail");
        assertThat(h.model.users.get(0)).endsWith("[advised]");
    }

    @Test
    void localQuotaSlidesAndRejectsOversizedRequests() {
        long[] now = {0};
        LocalTokenQuota q = new LocalTokenQuota(100, () -> now[0]);
        assertThat(q.tryAcquire(500)).isFalse();
        assertThat(q.tryAcquire(100)).isTrue();
        assertThat(q.tryAcquire(1)).isFalse();
        now[0] = 61_000_000_000L;
        assertThat(q.tryAcquire(90)).isTrue();
        assertThat(q.tryAcquire(20)).isFalse();
    }

    @Test
    void retriesAndRepairsEachRequireTheirOwnQuotaAdmission() {
        for (boolean retry : List.of(true, false)) {
            Harness h = new Harness("m1", "m2", p -> p.withResilience(p.resilience()
                    .withQuota(new LlmProperties.Quota(4_000))));
            h.model.behaviour = n -> {
                if (retry) throw new TransientAiException("503 provider unavailable");
                return "not-json";
            };
            assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                    e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.QUOTA));
            assertThat(h.model.calls.get()).isEqualTo(1);
        }
    }

    @Test
    void invalidPrimaryResponsesStillCountInTheBudgetAndSuccessfulFallbackUsage() {
        Harness h = withFallback();
        var price = new AgentProperties.Pricing();
        price.setInputPer1k(1);
        price.setOutputPer1k(2);
        h.agents.getPricing().put("m1", price);
        h.agents.getPricing().put("m2", price);
        h.model.behaviour = n -> n <= 2 ? "not-json" : GOOD;
        var budget = new CaseBudget(10_000, 100, 0);
        var result = h.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget));
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(result.providerCalls()).isEqualTo(3);
        assertThat(result.promptTokens()).isEqualTo(30);
        assertThat(result.completionTokens()).isEqualTo(15);
        assertThat(budget.tokensUsed()).isEqualTo(45);
        assertThat(budget.costUsd()).isEqualTo(.06);
        assertThat(result.costUsd()).isCloseTo(.06, org.assertj.core.data.Offset.offset(.0000001));
    }

    @Test
    void missingUsageIsConservativelyChargedRatherThanBypassingCaseBudgets() {
        Harness h = same();
        h.model.reportUsage = false;
        var budget = new CaseBudget(4000, 0, 0);
        h.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget));
        assertThat(budget.tokensUsed()).isGreaterThan(2048);
        assertThatThrownBy(() -> h.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget)))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BUDGET));
        assertThat(h.model.calls.get()).isEqualTo(1);
        assertThat(h.registry.find("llm.usage.estimated").counter().count()).isEqualTo(1);
    }

    @Test void partiallyMissingUsageRetainsKnownTokensAndEstimatesTheMissingDimension() {
        Harness h = same();
        h.model.usage = org.mockito.Mockito.mock(org.springframework.ai.chat.metadata.Usage.class);
        org.mockito.Mockito.when(h.model.usage.getPromptTokens()).thenReturn(10);
        org.mockito.Mockito.when(h.model.usage.getCompletionTokens()).thenReturn(null);
        var budget = new CaseBudget(4000, 0, 0);
        var reply = h.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget));
        assertThat(reply.promptTokens()).isEqualTo(10);
        assertThat(reply.completionTokens()).isZero();
        assertThat(budget.tokensUsed()).isEqualTo(2058);
        assertThat(h.registry.get("llm.usage.estimated").counter().count()).isEqualTo(1);
    }

    @Test
    void concurrentCallsReserveBudgetBeforeEnteringTheProviderAndReleaseUnusedCapacity() throws Exception {
        Harness h = same();
        var budget = new CaseBudget(4000, 0, 0);
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        h.model.behaviour = n -> {
            inside.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test worker not released"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return GOOD;
        };
        var spec = AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> h.gateway.call(spec));
            try {
                assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(h.registry.find("llm.calls.inflight").gauge().value()).isEqualTo(1);
                assertThatThrownBy(() -> h.gateway.call(spec)).isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BUDGET));
            } finally { release.countDown(); }
            assertThat(first.get().value().answer()).isEqualTo("ok");
        }
        h.gateway.call(spec);
        assertThat(h.model.calls.get()).isEqualTo(2);
        assertThat(budget.tokensUsed()).isEqualTo(30);
        assertThat(h.registry.find("llm.calls.inflight").gauge().value()).isZero();
    }

    @Test
    void costLimitedCasesRequireExplicitPricingAndQuotaRefusalsDoNotChargeUnsentCalls() {
        Harness h = same();
        assertThatThrownBy(() -> h.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0)
                .withBudget(new CaseBudget(0, .1, 0)))).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BUDGET));
        assertThat(h.model.calls.get()).isZero();
        Harness limited = new Harness("m1", "m1", p -> p.withResilience(p.resilience().withQuota(new LlmProperties.Quota(1))));
        var budget = CaseBudget.unlimited();
        assertThatThrownBy(() -> limited.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget)))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.QUOTA));
        assertThat(budget.tokensUsed()).isZero();
        assertThat(limited.model.calls.get()).isZero();
    }

    @Test void ambiguousProviderFailuresConsumeTheEstimateWithoutDoubleCharging() {
        Harness h = same();
        h.model.behaviour = n -> { throw new NonTransientAiException("400 rejected by provider"); };
        var budget = new CaseBudget(4000, 0, 0);
        assertThatThrownBy(() -> h.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget)))
                .isInstanceOf(LlmException.class);
        assertThat(h.model.calls.get()).isEqualTo(1);
        assertThat(budget.tokensUsed()).isBetween(2049L, 3999L);
        assertThat(h.registry.get("llm.usage.estimated").counter().count()).isEqualTo(1);
    }

    @Test void quotaStorageFailureFailsClosedWithoutChargingOrPoisoningTheBreaker() {
        Harness h = same();
        h.gateway = new LlmGateway(() -> ChatClient.builder(h.model), h.agents, h.props,
                new AgentMetrics(h.registry, h.agents), new PlatformEventPublisher(event -> {}),
                new PromptRegistry(), estimate -> {
                    throw new org.springframework.dao.DataAccessResourceFailureException("database unavailable");
                }, List.of(), h.registry);
        var budget = new CaseBudget(4000, 0, 0);
        assertThatThrownBy(() -> h.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget)))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.QUOTA));
        assertThat(h.model.calls.get()).isZero();
        assertThat(budget.tokensUsed()).isZero();
        assertThat(h.gateway.snapshot().get("breakers").toString()).contains("bufferedCalls=0");
    }

    @Test void unmeteredToolLoopsCannotBypassConfiguredUsageLimits() {
        for (boolean providerQuota : List.of(false, true)) {
            Harness h = new Harness("m1", "m1", p -> providerQuota
                    ? p.withResilience(p.resilience().withQuota(new LlmProperties.Quota(10_000))) : p);
            var spec = AgentCallSpec.of("a", "s", "u", Out.class, 0).withTools(new ReadOnlyTool())
                    .withBudget(providerQuota ? CaseBudget.unlimited() : new CaseBudget(4000, 0, 0));
            assertThatThrownBy(() -> h.gateway.call(spec)).isInstanceOfSatisfying(LlmException.class,
                    e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.BUDGET));
            assertThat(h.model.calls.get()).isZero();
        }
        Harness unbounded = same();
        unbounded.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0).withTools(new ReadOnlyTool()));
        assertThat(unbounded.model.calls.get()).isEqualTo(1);
    }

    static class ReadOnlyTool {
        @org.springframework.ai.tool.annotation.Tool(description = "Read a test status")
        public String status() { return "ok"; }
    }

    @Test void engagingKillSwitchPreventsSubsequentRetryAndFallbackAttempts() {
        Harness h = withFallback();
        h.model.behaviour = n -> {
            h.gateway.setKillSwitch(true);
            throw new TransientAiException("503 provider unavailable");
        };
        assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.KILL_SWITCH));
        assertThat(h.model.calls.get()).isEqualTo(1);
    }

    @Test void providerErrorBudgetStopsRetriesAndOtherRoutesWithoutChargingUnsentWork() {
        Harness h = withFallback();
        h.gateway.configureProviderErrorBudget(new ProviderErrorBudgetProperties(true, 50, 4, 2, 60_000, 1));
        h.model.behaviour = n -> { throw new TransientAiException("503 provider unavailable"); };
        var budget = new CaseBudget(40_000, 0, 0);
        var spec = AgentCallSpec.of("a", "s", "u", Out.class, 0).withBudget(budget);
        assertThatThrownBy(() -> h.gateway.call(spec)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PROVIDER_BUDGET));
        assertThat(h.model.calls.get()).isEqualTo(2);
        assertThat(h.model.modelsSeen).containsOnly("m1");
        long used = budget.tokensUsed();
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> h.gateway.call(spec)).isInstanceOfSatisfying(LlmException.class,
                    e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PROVIDER_BUDGET));
        }
        assertThat(h.model.calls.get()).isEqualTo(2);
        assertThat(budget.tokensUsed()).isEqualTo(used);
        assertThat(h.registry.get("llm.provider.budget.state").gauge().value()).isEqualTo(2);
        assertThat(h.gateway.snapshot().get("providerErrorBudget").toString()).contains("state=OPEN", "bufferedCalls=2");
        assertThat(h.events).anyMatch(e -> e.type().equals("llm.provider.budget.changed"));
        assertThat(h.gateway.killSwitchOn()).isFalse();
    }

    @Test void providerHealthBudgetIgnoresLocalAdmissionAndAgentOutputFailures() {
        Harness h = same();
        h.gateway.configureProviderErrorBudget(new ProviderErrorBudgetProperties(true, 50, 4, 2, 60_000, 1));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> h.gateway.call(AgentCallSpec.of("a", "s", "u", Out.class, 0)
                    .withBudget(new CaseBudget(1, 0, 0)))).isInstanceOf(LlmException.class);
        }
        assertThat(h.gateway.snapshot().get("providerErrorBudget").toString()).contains("bufferedCalls=0");
        h.model.behaviour = n -> "not-json";
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                    e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.OUTPUT_INVALID));
        }
        assertThat(h.gateway.snapshot().get("providerErrorBudget").toString()).contains("state=CLOSED", "failureRate=0.0");
        assertThat(h.model.calls.get()).isEqualTo(8);
    }

    @Test void providerHealthRecoversThroughBoundedProbesButNeverReleasesTheManualKillSwitch() {
        Harness h = same();
        h.gateway.configureProviderErrorBudget(new ProviderErrorBudgetProperties(true, 50, 2, 2, 50, 1));
        h.model.behaviour = n -> { throw new TransientAiException("503 provider unavailable"); };
        assertThatThrownBy(h::call).isInstanceOf(LlmException.class);
        assertThat(h.model.calls.get()).isEqualTo(2);
        h.gateway.setKillSwitch(true);
        h.model.behaviour = n -> GOOD;
        assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.KILL_SWITCH));
        assertThat(h.model.calls.get()).isEqualTo(2);
        h.gateway.setKillSwitch(false);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(h.call().value().answer()).isEqualTo("ok"));
        assertThat(h.model.calls.get()).isEqualTo(3);
        assertThat(h.gateway.snapshot().get("providerErrorBudget").toString()).contains("state=CLOSED");
        assertThat(h.registry.get("llm.provider.budget.state").gauge().value()).isZero();
    }

    @Test void providerErrorBudgetRejectsInvalidThresholdsAtBinding() {
        assertThatThrownBy(() -> new ProviderErrorBudgetProperties(true, Float.NaN, 20, 10, 30_000, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderErrorBudgetProperties(true, 50, 20, 21, 30_000, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderErrorBudgetProperties(true, 50, 20, 10, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void halfOpenProviderBudgetAllowsOnlyTheConfiguredConcurrentProbe() throws Exception {
        Harness h = withFallback();
        h.gateway.configureProviderErrorBudget(new ProviderErrorBudgetProperties(true, 50, 2, 2, 50, 1));
        h.model.behaviour = n -> { throw new TransientAiException("503 provider unavailable"); };
        assertThatThrownBy(h::call).isInstanceOf(LlmException.class);
        var probing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        h.model.behaviour = n -> {
            probing.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Probe was not released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return GOOD;
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var probe = workers.submit(() -> org.awaitility.Awaitility.await()
                    .atMost(java.time.Duration.ofSeconds(3)).untilAsserted(() ->
                            assertThat(h.call().value().answer()).isEqualTo("ok")));
            try {
                assertThat(probing.await(3, TimeUnit.SECONDS)).isTrue();
                assertThat(h.registry.get("llm.provider.budget.state").gauge().value()).isEqualTo(1);
                assertThatThrownBy(h::call).isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.kind()).isEqualTo(LlmException.Kind.PROVIDER_BUDGET));
                assertThat(h.model.calls.get()).isEqualTo(3);
            } finally { release.countDown(); }
            probe.get();
        }
        assertThat(h.gateway.snapshot().get("providerErrorBudget").toString()).contains("state=CLOSED");
    }

    @Test
    void classifierRetriesOnlyTransientFailures() {
        assertThat(LlmErrorClassifier.retryable(new TransientAiException("x"))).isTrue();
        assertThat(LlmErrorClassifier.retryable(new NonTransientAiException("429 rate limit"))).isTrue();
        assertThat(LlmErrorClassifier.retryable(new NonTransientAiException("400 bad"))).isFalse();
        assertThat(LlmErrorClassifier.retryable(new IllegalStateException("x"))).isFalse();
        assertThat(LlmErrorClassifier.retryable(new LlmException(LlmException.Kind.TIMEOUT, "t"))).isFalse();
        assertThat(LlmErrorClassifier.countsAsProviderFailure(new LlmException(LlmException.Kind.TIMEOUT, "t"))).isTrue();
        assertThat(LlmErrorClassifier.countsAsProviderFailure(new LlmException(LlmException.Kind.EMPTY_RESPONSE, "t"))).isFalse();
    }
}
