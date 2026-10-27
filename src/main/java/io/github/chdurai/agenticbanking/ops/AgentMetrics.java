package io.github.chdurai.agenticbanking.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.chdurai.agenticbanking.config.AgentProperties;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * AIOps / FinOps telemetry: per-agent latency, token consumption (prompt vs completion),
 * estimated cost, and decision/outcome distributions. Exposed via Micrometer
 * (/actuator/metrics), summarized at /api/ops/metrics, costed at /api/ops/cost.
 */
@Component
public class AgentMetrics {

    private static final class UsageTotals {
        final LongAdder calls = new LongAdder();
        final LongAdder promptTokens = new LongAdder();
        final LongAdder completionTokens = new LongAdder();
        final DoubleAdder costUsd = new DoubleAdder();
    }

    private final MeterRegistry registry;
    private final AgentProperties properties;
    private final Map<String, UsageTotals> usageByAgent = new ConcurrentHashMap<>();
    private final Map<String, UsageTotals> usageByModel = new ConcurrentHashMap<>();

    @Autowired
    public AgentMetrics(MeterRegistry registry, AgentProperties properties) {
        this.registry = registry;
        this.properties = properties;
        properties.getPricing().forEach((model, price) -> {
            if (!Double.isFinite(price.getInputPer1k()) || price.getInputPer1k() < 0
                    || !Double.isFinite(price.getOutputPer1k()) || price.getOutputPer1k() < 0) {
                throw new IllegalArgumentException("Invalid model pricing: " + model);
            }
        });
    }

    public AgentMetrics(MeterRegistry registry) {
        this(registry, new AgentProperties());
    }

    /** Record one model call from the provider's response (usage may be absent). */
    public void record(String agent, String model, long durationMs, ChatResponse response) {
        long prompt = 0;
        long completion = 0;
        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                prompt = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                completion = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            }
        }
        recordCall(agent, model, durationMs, prompt, completion);
    }

    public void recordCall(String agent, String model, long durationMs, long promptTokens, long completionTokens) {
        Timer.builder("agent.execution").tag("agent", agent).register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
        if (promptTokens > 0) {
            Counter.builder("agent.tokens").tag("agent", agent).tag("direction", "prompt")
                    .register(registry).increment(promptTokens);
        }
        if (completionTokens > 0) {
            Counter.builder("agent.tokens").tag("agent", agent).tag("direction", "completion")
                    .register(registry).increment(completionTokens);
        }
        double cost = costOf(model, promptTokens, completionTokens);
        if (cost > 0) {
            Counter.builder("agent.cost.usd").tag("model", model).register(registry).increment(cost);
        }
        if (!hasPricing(model) && promptTokens + completionTokens > 0) {
            Counter.builder("agent.usage.unpriced").tag("model", String.valueOf(model)).register(registry).increment();
        }
        for (UsageTotals u : new UsageTotals[]{
                usageByAgent.computeIfAbsent(agent, k -> new UsageTotals()),
                usageByModel.computeIfAbsent(String.valueOf(model), k -> new UsageTotals())}) {
            u.calls.increment();
            u.promptTokens.add(promptTokens);
            u.completionTokens.add(completionTokens);
            u.costUsd.add(cost);
        }
    }

    public double costOf(String model, long promptTokens, long completionTokens) {
        AgentProperties.Pricing p = model == null ? null : properties.getPricing().get(model);
        if (p == null) {
            return 0;
        }

        return promptTokens / 1000.0 * p.getInputPer1k() + completionTokens / 1000.0 * p.getOutputPer1k();
    }

    public boolean hasPricing(String model) {
        return model != null && properties.getPricing().containsKey(model);
    }

    public void recordDecision(String decision) {
        Counter.builder("loan.decisions").tag("outcome", decision).register(registry).increment();
    }

    public void recordIncident(String severity, String status) {
        Counter.builder("incident.handled")
                .tag("severity", severity).tag("status", status)
                .register(registry).increment();
    }

    public void recordGuardrailBlock(String flow) {
        Counter.builder("guardrail.blocks").tag("flow", flow).register(registry).increment();
    }

    public void recordCounter(String name, String tagKey, String tagValue) {
        Counter.builder(name).tag(tagKey, tagValue).register(registry).increment();
    }

    /** FinOps view: calls, tokens and estimated USD per agent and per model. */
    public Map<String, Object> costReport() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("note", "Estimated from agents.pricing (illustrative per-1K-token prices); "
                + "models without a price entry cost 0.");
        out.put("byAgent", render(usageByAgent));
        out.put("byModel", render(usageByModel));
        double total = usageByAgent.values().stream().mapToDouble(u -> u.costUsd.sum()).sum();
        out.put("totalEstimatedUsd", round(total));
        return out;
    }

    private static Map<String, Object> render(Map<String, UsageTotals> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            UsageTotals u = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("calls", u.calls.sum());
            row.put("promptTokens", u.promptTokens.sum());
            row.put("completionTokens", u.completionTokens.sum());
            row.put("estimatedUsd", round(u.costUsd.sum()));
            out.put(e.getKey(), row);
        });
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }

    /** Compact summary for the ops endpoint. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        registry.getMeters().forEach(meter -> {
            String name = meter.getId().getName();
            String tags = meter.getId().getTags().stream()
                    .map(t -> t.getKey() + "=" + t.getValue())
                    .reduce((a, b) -> a + "," + b).orElse("");
            String key = tags.isEmpty() ? name : name + "{" + tags + "}";
            if (meter instanceof Timer t) {
                out.put(key, Map.of(
                        "count", t.count(),
                        "avgMs", Math.round(t.mean(TimeUnit.MILLISECONDS)),
                        "maxMs", Math.round(t.max(TimeUnit.MILLISECONDS))));
            } else if (meter instanceof Counter c) {
                out.put(key, Math.round(c.count()));
            }
        });
        return out;
    }
}
