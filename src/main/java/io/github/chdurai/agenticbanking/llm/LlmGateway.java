package io.github.chdurai.agenticbanking.llm;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import io.github.chdurai.agenticbanking.agents.AgentJsonSupport;
import io.github.chdurai.agenticbanking.config.AgentProperties;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The single front door for every LLM call (WI-06). Agents describe <em>what</em> they need
 * ({@link AgentCallSpec}); the gateway owns <em>how</em>: routing, kill switch, budgets,
 * quota, bulkhead, jittered retry on transient errors only, per-model circuit breaker, hard
 * timeout, ordered fallback, one schema-repair attempt, and provenance on every result.
 *
 * <pre>
 * kill switch → budget → bulkhead ─┬─ model[0]: retry( breaker( budget + quota + timed call ) ) → parse/repair
 *                                          └─ model[1] (fallback, only if different)
 * </pre>
 *
 * Callers receive either an {@link LlmResult} or an {@link LlmException}; never a hang.
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final Supplier<ChatClient.Builder> clients;
    private final AgentProperties agentProps;
    private final LlmProperties props;
    private final AgentMetrics metrics;
    private final PlatformEventPublisher events;
    private final PromptRegistry prompts;
    private final QuotaLimiter quota;
    private final List<LlmCallAdvisor> advisors;
    private final MeterRegistry registry;

    /** Live-observability sink for the Mission Control LLM inspector; absent in tests/legacy wiring. */
    private volatile io.github.chdurai.agenticbanking.ops.AgentActivityHub activity;
    @Autowired(required = false)
    private io.github.chdurai.agenticbanking.investigation.InvestigationStore lineage;

    private final AtomicBoolean killSwitch;
    private final java.util.concurrent.atomic.AtomicInteger providerInFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private CircuitBreaker providerErrorBudget;
    private JdbcProviderHealthBudget sharedProviderHealth;
    private final Bulkhead bulkhead;
    private final ExecutorService callPool = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public LlmGateway(ObjectProvider<ChatClient.Builder> clients, AgentProperties agentProps,
                      LlmProperties props, AgentMetrics metrics, PlatformEventPublisher events,
                      PromptRegistry prompts, QuotaLimiter quota,
                      ObjectProvider<LlmCallAdvisor> advisors, MeterRegistry registry) {
        this(clients::getObject, agentProps, props, metrics, events, prompts, quota,
                advisors.orderedStream().toList(), registry);
    }

    LlmGateway(Supplier<ChatClient.Builder> clients, AgentProperties agentProps, LlmProperties props,
               AgentMetrics metrics, PlatformEventPublisher events, PromptRegistry prompts,
               QuotaLimiter quota, List<LlmCallAdvisor> advisors, MeterRegistry registry) {
        this.clients = clients;
        this.agentProps = agentProps;
        this.props = props;
        this.metrics = metrics;
        this.events = events;
        this.prompts = prompts;
        this.quota = quota;
        this.advisors = advisors;
        this.registry = registry;
        this.killSwitch = new AtomicBoolean(props.resilience().killSwitch());
        LlmProperties.Bulkhead b = props.resilience().bulkhead();
        this.bulkhead = Bulkhead.of("llm-" + props.provider(), BulkheadConfig.custom()
                .maxConcurrentCalls(Math.max(1, b.maxConcurrent()))
                .maxWaitDuration(Duration.ofMillis(Math.max(0, b.maxWaitMs()))).build());
        Gauge.builder("llm.bulkhead.available", bulkhead,
                bh -> bh.getMetrics().getAvailableConcurrentCalls()).register(registry);
        Gauge.builder("llm.killswitch", killSwitch, k -> k.get() ? 1 : 0).register(registry);
        Gauge.builder("llm.calls.inflight", providerInFlight, java.util.concurrent.atomic.AtomicInteger::get).register(registry);
    }

    @Autowired(required = false)
    void setActivity(io.github.chdurai.agenticbanking.ops.AgentActivityHub activity) {
        this.activity = activity;
    }

    @Autowired
    void configureProviderErrorBudget(ProviderErrorBudgetProperties config) {
        if (!config.enabled()) return;
        if (config.shared()) return;
        if (providerErrorBudget != null) throw new IllegalStateException("Provider error budget already configured");
        providerErrorBudget = CircuitBreaker.of("provider-budget-" + props.provider(), CircuitBreakerConfig.custom()
                .failureRateThreshold(config.failureRatePercent())
                .slidingWindowSize(config.slidingWindowSize()).minimumNumberOfCalls(config.minimumCalls())
                .waitDurationInOpenState(Duration.ofMillis(config.waitOpenMs()))
                .permittedNumberOfCallsInHalfOpenState(config.halfOpenCalls())
                .ignoreException(t -> !LlmErrorClassifier.countsAsProviderFailure(t)).build());
        Gauge.builder("llm.provider.budget.state", providerErrorBudget, c -> switch (c.getState()) {
            case CLOSED, METRICS_ONLY, DISABLED -> 0;
            case HALF_OPEN -> 1;
            case OPEN, FORCED_OPEN -> 2;
        }).tag("provider", props.provider()).register(registry);
        providerErrorBudget.getEventPublisher().onStateTransition(event -> {
            log.warn("[llm] provider {} error budget {}", props.provider(), event.getStateTransition());
            publish("llm.provider.budget.changed", "platform", props.provider(),
                    "Provider health admission changed to " + event.getStateTransition().getToState());
        });
    }

    @Autowired
    void configureSharedProviderHealth(ProviderErrorBudgetProperties config,
                                       java.util.Optional<JdbcProviderHealthBudget> shared) {
        if (!config.shared()) return;
        sharedProviderHealth = shared.orElseThrow(() ->
                new IllegalStateException("Shared provider health requires JDBC persistence"));
        Gauge.builder("llm.provider.budget.state", sharedProviderHealth, JdbcProviderHealthBudget::stateMetric)
                .tag("provider", props.provider()).register(registry);
    }

    // ---------------------------------------------------------------- configuration checks

    /** Fails startup on configuration that would silently weaken resilience or governance. */
    @PostConstruct
    void validate() {
        List<String> problems = new ArrayList<>();
        List<String> approved = props.providers().approved();
        if (!approved.isEmpty() && !approved.contains(props.provider())) {
            problems.add("provider '" + props.provider() + "' is not in platform.llm.providers.approved " + approved);
        }
        for (String agent : knownAgents()) {
            Route r = route(agent);
            if (r.primary().equals(r.fallback())) {
                if (props.allowFallbackEqualsPrimary()) {
                    log.warn("[llm] {}: fallback model equals primary '{}' - no model-level redundancy "
                            + "(platform.llm.allow-fallback-equals-primary=true)", agent, r.primary());
                } else {
                    problems.add(agent + ": fallback model equals primary '" + r.primary()
                            + "'; configure a distinct fallback or set platform.llm.allow-fallback-equals-primary=true");
                }
            }
            if (!prompts.exists(agent, r.promptVersion())) {
                problems.add(agent + ": prompt version '" + r.promptVersion() + "' not found");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid LLM configuration:\n - " + String.join("\n - ", problems));
        }
    }

    private Set<String> knownAgents() {
        Set<String> s = new LinkedHashSet<>(agentProps.getModels().keySet());
        s.addAll(props.routes().keySet());
        return s;
    }

    // ---------------------------------------------------------------- routing

    record Route(String primary, String fallback, Double temperature, Integer maxTokens, String promptVersion) {
        List<String> attempts() {
            return primary.equals(fallback) ? List.of(primary) : List.of(primary, fallback);
        }
    }

    Route route(String agentId) {
        LlmProperties.Route r = props.routes().get(agentId);
        String primary = r != null && r.primary() != null ? r.primary() : agentProps.modelFor(agentId);
        String fallback = r != null && r.fallback() != null ? r.fallback() : agentProps.getFallbackModel();
        return new Route(primary, fallback, r == null ? null : r.temperature(),
                r == null ? null : r.maxTokens(), r == null ? null : r.promptVersion());
    }

    // ---------------------------------------------------------------- kill switch

    public boolean killSwitchOn() {
        return killSwitch.get();
    }

    public void setKillSwitch(boolean on) {
        if (killSwitch.getAndSet(on) != on) {
            log.warn("[llm] kill switch {}", on ? "ENGAGED" : "released");
            publish("llm.killswitch", "platform", "llm", on ? "LLM kill switch engaged" : "LLM kill switch released");
        }
    }

    /** Operational snapshot for /api/ops/llm. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", props.provider());
        out.put("killSwitch", killSwitch.get());
        out.put("providerErrorBudget", sharedProviderHealth != null ? sharedProviderHealth.snapshot()
                : providerErrorBudget == null ? Map.of("enabled", false)
                : Map.of("enabled", true, "state", providerErrorBudget.getState().name(),
                        "bufferedCalls", providerErrorBudget.getMetrics().getNumberOfBufferedCalls(),
                        "failureRate", providerErrorBudget.getMetrics().getFailureRate()));
        out.put("bulkheadAvailable", bulkhead.getMetrics().getAvailableConcurrentCalls());
        Map<String, Object> bs = new LinkedHashMap<>();
        breakers.forEach((k, cb) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("state", cb.getState().name());
            row.put("failureRate", cb.getMetrics().getFailureRate());
            row.put("bufferedCalls", cb.getMetrics().getNumberOfBufferedCalls());
            bs.put(k, row);
        });
        out.put("breakers", bs);
        Map<String, Object> routes = new LinkedHashMap<>();
        knownAgents().forEach(a -> {
            Route r = route(a);
            routes.put(a, Map.of("primary", r.primary(), "fallback", r.fallback(),
                    "promptVersion", r.promptVersion() == null ? PromptRegistry.INLINE_VERSION : r.promptVersion()));
        });
        out.put("routes", routes);
        return out;
    }

    // ---------------------------------------------------------------- the call

    public <T> LlmResult<T> call(AgentCallSpec<T> input) {
        CaseBudget callerBudget = io.github.chdurai.agenticbanking.security.CallerContext.current().budget();
        if (callerBudget != null && callerBudget.durable() && input.budget() != null && input.budget() != callerBudget) {
            throw fail(input, new LlmException(LlmException.Kind.BUDGET,
                    "An explicit call budget cannot replace the durable case spending envelope"));
        }
        final AgentCallSpec<T> spec = input.budget() == null && io.github.chdurai.agenticbanking.security.CallerContext.current().budget() != null
                ? input.withBudget(io.github.chdurai.agenticbanking.security.CallerContext.current().budget()) : input;
        String agent = spec.agentId();
        long started = System.nanoTime();
        if (killSwitch.get()) {
            throw fail(spec, new LlmException(LlmException.Kind.KILL_SWITCH, "LLM kill switch is on"));
        }
        try { checkBudget(spec); }
        catch (LlmException e) { throw fail(spec, e); }
        if (!spec.tools().isEmpty() && (props.resilience().quota().tpm() > 0
                || spec.budget() != null && (spec.budget().usageLimited() || spec.budget().durable()))) {
            throw fail(spec, new LlmException(LlmException.Kind.BUDGET,
                    "Model-managed tool loops cannot be admitted under token/cost limits; use code-first evidence"));
        }

        Route route = route(agent);
        PromptRegistry.Prompt prompt = prompts.resolve(agent, spec.system(), route.promptVersion());
        String advised = spec.user();
        for (LlmCallAdvisor a : advisors) {
            advised = a.beforeCall(agent, prompt.text(), advised);
        }
        final String user = advised;
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(spec.outputType());
        String system = prompt.text() + "\n" + converter.getFormat();
        double temperature = route.temperature() != null ? route.temperature() : spec.temperature();
        int maxTokens = spec.maxTokens() != null ? spec.maxTokens() : route.maxTokens() != null ? route.maxTokens() : 2048;
        if (maxTokens < 1) throw new IllegalArgumentException("Model output token limit must be positive");

        try {
            LlmResult<T> result = bulkhead.executeSupplier(() ->
                    withFallback(spec, route, prompt, system, user, temperature, maxTokens, converter, started));
            observe(spec, system, user, result);
            return result;
        } catch (BulkheadFullException e) {
            LlmException failure = new LlmException(LlmException.Kind.BULKHEAD_FULL,
                    "LLM concurrency limit reached", e);
            observeFailure(spec, system, user, started, failure);
            throw fail(spec, failure);
        } catch (RuntimeException e) {
            observeFailure(spec, system, user, started, e);
            throw e;
        }
    }

    private <T> void observe(AgentCallSpec<T> spec, String system, String user, LlmResult<T> result) {
        var hub = activity;
        if (hub == null) return;
        try {
            hub.llmExchange(spec.flow(), spec.subjectId(), spec.agentId(), system, user, result);
        } catch (RuntimeException e) {
            log.debug("activity observation failed: {}", e.getMessage());
        }
    }

    private <T> void observeFailure(AgentCallSpec<T> spec, String system, String user, long started,
                                    RuntimeException e) {
        var hub = activity;
        if (hub == null) return;
        try {
            hub.llmFailure(spec.flow(), spec.subjectId(), spec.agentId(), system, user,
                    (System.nanoTime() - started) / 1_000_000, e);
        } catch (RuntimeException ignored) {
            // observation must never mask the real failure
        }
    }

    private <T> LlmResult<T> withFallback(AgentCallSpec<T> spec, Route route, PromptRegistry.Prompt prompt,
                                          String system, String user, double temperature, Integer maxTokens,
                                          BeanOutputConverter<T> converter, long started) {
        List<String> models = route.attempts();
        LlmException last = null;
        int providerCalls = 0;
        var usage = new CallUsage();
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            int[] calls = {0};
            try {
                T value = attempt(spec, model, system, user, temperature, maxTokens, converter, calls, usage);
                providerCalls += calls[0];
                metrics.recordCounter("llm.calls", "outcome", i == 0 ? "primary" : "fallback");
                if (i > 0) {
                    publish("llm.fallback.used", spec.flow(), spec.subjectId(),
                            spec.agentId() + " served by fallback " + model + " after: " + last.getMessage());
                }
                return new LlmResult<>(value, spec.agentId(), model, prompt.version(), prompt.hash(),
                        usage.prompt, usage.completion, (System.nanoTime() - started) / 1_000_000,
                        usage.cost, i > 0, providerCalls);
            } catch (LlmException e) {
                providerCalls += calls[0];
                if (e.kind() == LlmException.Kind.BUDGET || e.kind() == LlmException.Kind.QUOTA
                        || e.kind() == LlmException.Kind.KILL_SWITCH
                        || e.kind() == LlmException.Kind.PROVIDER_BUDGET) throw fail(spec, e);
                last = e;
                log.warn("[{}] model={} failed ({}): {}", spec.agentId(), model, e.kind(), e.getMessage());
            }
        }
        throw fail(spec, last);
    }

    private static final class CallUsage {
        long prompt;
        long completion;
        double cost;
    }

    private <T> T attempt(AgentCallSpec<T> spec, String model, String system, String user,
                                   double temperature, Integer maxTokens, BeanOutputConverter<T> converter,
                                   int[] calls, CallUsage usage) {
        String userMessage = user;
        LlmException invalid = null;
        for (int pass = 0; pass < 2; pass++) {
            ModelReply reply = protectedCall(spec, model, system, userMessage, temperature, maxTokens, calls, usage);
            try {
                for (LlmCallAdvisor a : advisors) {
                    a.afterCall(spec.agentId(), reply.text);
                }
                return converter.convert(AgentJsonSupport.extractJson(reply.text));
            } catch (LlmException e) {
                throw e;
            } catch (RuntimeException parse) {
                invalid = new LlmException(LlmException.Kind.OUTPUT_INVALID,
                        "Model output did not match " + spec.outputType().getSimpleName() + ": " + parse.getMessage(), parse);
                metrics.recordCounter("llm.repairs", "agent", spec.agentId());
                userMessage = user + "\n\nYour previous reply was rejected: " + shorten(parse.getMessage())
                        + "\nReply again with ONLY one valid JSON object that matches the schema exactly.";
            }
        }
        throw invalid;
    }

    private record ModelReply(String text) {}

    /** retry( breaker( timed call ) ) for one model, translating every failure to LlmException. */
    private ModelReply protectedCall(AgentCallSpec<?> spec, String model, String system, String user,
                                     double temperature, Integer maxTokens, int[] calls, CallUsage usage) {
        CircuitBreaker breaker = breakerFor(model);
        Supplier<ModelReply> timed = () -> {
            Supplier<ModelReply> work = () -> timedCall(spec, model, system, user, temperature, maxTokens, calls, usage);
            if (sharedProviderHealth != null) return sharedProviderHealth.execute(work);
            if (providerErrorBudget == null) return work.get();
            try { return providerErrorBudget.executeSupplier(work); }
            catch (CallNotPermittedException e) {
                throw new LlmException(LlmException.Kind.PROVIDER_BUDGET,
                        "Provider health error budget exhausted", e);
            }
        };
        Supplier<ModelReply> decorated = Retry.decorateSupplier(retryFor(spec.agentId()),
                CircuitBreaker.decorateSupplier(breaker, timed));
        try {
            return decorated.get();
        } catch (LlmException e) {
            throw e;
        } catch (CallNotPermittedException e) {
            throw new LlmException(LlmException.Kind.BREAKER_OPEN,
                    "Circuit breaker open for " + props.provider() + ":" + model, e);
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Kind.PROVIDER, summarize(e), e);
        }
    }

    private ModelReply timedCall(AgentCallSpec<?> spec, String model, String system, String user,
                                 double temperature, Integer maxTokens, int[] calls, CallUsage usage) {
        if (killSwitch.get()) throw new LlmException(LlmException.Kind.KILL_SWITCH, "LLM kill switch is on");
        checkBudget(spec);
        long estimatedPrompt = 64L + system.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + user.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        long estimatedCompletion = maxTokens == null ? 2048 : maxTokens;
        if (spec.budget() != null && spec.budget().costLimited() && !metrics.hasPricing(model)) {
            throw new LlmException(LlmException.Kind.BUDGET, "Model pricing is required for a cost-limited case");
        }
        double estimatedCost = metrics.costOf(model, estimatedPrompt, estimatedCompletion);
        try (var reservation = spec.budget() == null ? null
                : spec.budget().reserve(estimatedPrompt + estimatedCompletion, estimatedCost, metrics.hasPricing(model))) {
            boolean admitted;
            try { admitted = quota.tryAcquire(estimatedPrompt + estimatedCompletion); }
            catch (org.springframework.dao.DataAccessException e) {
                if (reservation != null) reservation.cancel();
                throw new LlmException(LlmException.Kind.QUOTA, "Shared quota storage unavailable", e);
            }
            if (!admitted) {
                if (reservation != null) reservation.cancel();
                throw new LlmException(LlmException.Kind.QUOTA, "Estimated token quota exhausted");
            }
            try {
                if (killSwitch.get()) throw new LlmException(LlmException.Kind.KILL_SWITCH, "LLM kill switch is on");
                checkBudget(spec);
                if (sharedProviderHealth != null) sharedProviderHealth.checkDispatch();
            } catch (LlmException e) {
                if (reservation != null) reservation.cancel();
                throw e;
            }
            long start = System.nanoTime();
            String exchange = lineage == null ? null : lineage.sourceIntent("MODEL", spec.agentId(), model,
                    "gateway-exchange-v1", Map.of("systemHash", io.github.chdurai.agenticbanking.cases.CaseService.sha256(system),
                            "inputHash", io.github.chdurai.agenticbanking.cases.CaseService.sha256(user)));
            ChatResponse response;
            try { response = awaitResponse(spec, model, system, user, temperature, maxTokens, calls); }
            catch (RuntimeException e) {
                metrics.recordCounter("llm.usage.estimated", "model", model);
                if (lineage != null && exchange != null) {
                    boolean uncertain = e instanceof LlmException failure
                            && (failure.kind() == LlmException.Kind.PROVIDER || failure.kind() == LlmException.Kind.TIMEOUT);
                    try { lineage.sourceResult(exchange, Map.of("error", e.getClass().getSimpleName(), "usage", "UNKNOWN_CONSERVATIVE_RESERVATION"),
                            uncertain ? "UNCERTAIN" : "FAILED"); }
                    catch (RuntimeException persistence) { e.addSuppressed(persistence); }
                }
                throw e;
            }
            long p = 0;
            long c = 0;
            boolean promptKnown = false;
            boolean completionKnown = false;
            if (response != null && response.getMetadata() != null) {
                Usage reported = response.getMetadata().getUsage();
                if (reported != null) {
                    promptKnown = reported.getPromptTokens() != null && reported.getPromptTokens() >= 0;
                    completionKnown = reported.getCompletionTokens() != null && reported.getCompletionTokens() >= 0;
                    p = promptKnown ? reported.getPromptTokens() : 0;
                    c = completionKnown ? reported.getCompletionTokens() : 0;
                }
            }
            usage.prompt += p;
            usage.completion += c;
            double cost = metrics.costOf(model, p, c);
            usage.cost += cost;
            metrics.recordCall(spec.agentId(), model, (System.nanoTime() - start) / 1_000_000, p, c);
            if (!promptKnown || !completionKnown || p + c == 0) {
                metrics.recordCounter("llm.usage.estimated", "model", model);
                long chargedPrompt = promptKnown && p + c > 0 ? p : estimatedPrompt;
                long chargedCompletion = completionKnown && p + c > 0 ? c : estimatedCompletion;
                if (reservation != null) reservation.settle(chargedPrompt + chargedCompletion,
                        metrics.costOf(model, chargedPrompt, chargedCompletion));
            } else if (reservation != null) reservation.settle(p + c, cost);
            String text = AgentJsonSupport.textOf(response);
            if (lineage != null) lineage.sourceResult(exchange, Map.of("output", text == null ? "" : text,
                    "promptTokens", p, "completionTokens", c, "estimatedCostUsd", cost,
                    "usageReported", promptKnown && completionKnown && p + c > 0,
                    "systemSha256", io.github.chdurai.agenticbanking.cases.CaseService.sha256(system),
                    "inputSha256", io.github.chdurai.agenticbanking.cases.CaseService.sha256(user)),
                    text == null || text.isBlank() ? "FAILED" : "SUCCEEDED");
            if (text == null || text.isBlank()) {
                throw new LlmException(LlmException.Kind.EMPTY_RESPONSE,
                        spec.agentId() + " model " + model + " returned an empty response"
                                + " (finishReason=" + (response == null || response.getResult() == null
                                ? "unknown" : response.getResult().getMetadata().getFinishReason())
                                + ", promptTokens=" + (promptKnown ? p : "unknown")
                                + ", completionTokens=" + (completionKnown ? c : "unknown")
                                + ", maxTokens=" + maxTokens + ")");
            }
            return new ModelReply(text);
        } catch (LlmException e) {
            if (e.kind() == LlmException.Kind.BUDGET) {
                publish("llm.budget.exceeded", spec.flow(), spec.subjectId(), spec.agentId() + " budget admission refused");
            }
            throw e;
        }
    }

    private ChatResponse awaitResponse(AgentCallSpec<?> spec, String model, String system, String user,
                                       double temperature, Integer maxTokens, int[] calls) {
        long timeoutMs = Math.min(props.resilience().timeoutMs(),
                spec.budget() == null ? Long.MAX_VALUE : Math.max(1, spec.budget().remainingMillis()));
        calls[0]++;
        String durableCaseId = io.github.chdurai.agenticbanking.cases.CaseExecution.current();
        Future<ChatResponse> future = callPool.submit(io.github.chdurai.agenticbanking.security.CallerContext.wrapCall(() -> {
            providerInFlight.incrementAndGet();
            io.github.chdurai.agenticbanking.ops.ProviderExchangeContext.set(
                    new io.github.chdurai.agenticbanking.ops.ProviderExchangeContext(spec.flow(), spec.subjectId(), spec.agentId()));
            try (var caseScope = io.github.chdurai.agenticbanking.cases.CaseExecution.open(durableCaseId)) {
                return invokeModel(spec, model, system, user, temperature, maxTokens);
            }
            finally {
                io.github.chdurai.agenticbanking.ops.ProviderExchangeContext.clear();
                providerInFlight.decrementAndGet();
            }
        }));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmException(LlmException.Kind.TIMEOUT,
                    spec.agentId() + " model " + model + " timed out after " + timeoutMs + "ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.PROVIDER, "Interrupted while calling " + model, e);
        } catch (CancellationException e) {
            throw new LlmException(LlmException.Kind.PROVIDER, "Call cancelled for " + model, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    private ChatResponse invokeModel(AgentCallSpec<?> spec, String model, String system, String user,
                                     double temperature, Integer maxTokens) {
        ToolCallingChatOptions.Builder options = ToolCallingChatOptions.builder().model(model).temperature(temperature);
        if (maxTokens != null) {
            options.maxTokens(maxTokens);
        }
        ChatClient.ChatClientRequestSpec request = clients.get().build().prompt()
                .messages(new SystemMessage(system), new UserMessage(user))
                .options(options.build());
        if (!spec.tools().isEmpty()) {
            request = request.tools(spec.tools().toArray());
        }
        return request.call().chatResponse();
    }

    // ---------------------------------------------------------------- resilience building blocks

    private Retry retryFor(String agentId) {
        LlmProperties.Retry r = props.resilience().retry();
        RetryConfig cfg = RetryConfig.custom()
                .maxAttempts(Math.max(1, r.max() + 1))
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        Math.max(1, r.initialBackoffMs()), Math.max(1.0, r.multiplier()), r.jitter()))
                .retryOnException(LlmErrorClassifier::retryable)
                .build();
        Retry retry = Retry.of("llm-" + agentId, cfg);
        retry.getEventPublisher().onRetry(e -> metrics.recordCounter("llm.retries", "agent", agentId));
        return retry;
    }

    private CircuitBreaker breakerFor(String model) {
        String key = props.provider() + ":" + model;
        return breakers.computeIfAbsent(key, k -> {
            LlmProperties.Breaker b = props.resilience().breaker();
            CircuitBreaker cb = CircuitBreaker.of(k, CircuitBreakerConfig.custom()
                    .failureRateThreshold(b.failureRatePercent())
                    .slidingWindowSize(Math.max(1, b.slidingWindowSize()))
                    .minimumNumberOfCalls(Math.max(1, b.minimumCalls()))
                    .waitDurationInOpenState(Duration.ofMillis(b.waitOpenMs()))
                    .permittedNumberOfCallsInHalfOpenState(Math.max(1, b.halfOpenCalls()))
                    .ignoreException(t -> !LlmErrorClassifier.countsAsProviderFailure(t))
                    .build());
            Gauge.builder("llm.breaker.state", cb, c -> switch (c.getState()) {
                case CLOSED, METRICS_ONLY, DISABLED -> 0;
                case HALF_OPEN -> 1;
                case OPEN, FORCED_OPEN -> 2;
            }).tag("route", k).register(registry);
            cb.getEventPublisher().onStateTransition(e -> {
                log.warn("[llm] breaker {} {}", k, e.getStateTransition());
                if (e.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                    publish("llm.breaker.opened", "platform", k, "Circuit breaker opened for " + k);
                }
            });
            return cb;
        });
    }

    // ---------------------------------------------------------------- helpers

    private void checkBudget(AgentCallSpec<?> spec) {
        if (spec.budget() == null) {
            return;
        }
        String exhausted = spec.budget().exhausted();
        if (exhausted != null) {
            publish("llm.budget.exceeded", spec.flow(), spec.subjectId(),
                    spec.agentId() + " blocked: case budget exhausted (" + exhausted + ")");
            throw new LlmException(LlmException.Kind.BUDGET, "Case budget exhausted: " + exhausted);
        }
    }

    private LlmException fail(AgentCallSpec<?> spec, LlmException e) {
        metrics.recordCounter("llm.failures", "kind", e.kind().name());
        return e;
    }

    private void publish(String type, String flow, String subject, String summary) {
        if (events != null) {
            events.publish(PlatformEvent.of(type, flow == null ? "platform" : flow, subject, summary));
        }
    }

    private static String summarize(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + shorten(m));
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ');
        return one.length() > 240 ? one.substring(0, 240) + "…" : one;
    }
}
