package io.github.chdurai.agenticbanking.ops;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.chdurai.agenticbanking.config.AgentProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityTest {

    @Test
    void metricsRecordAgentLatencyAndTokens() {
        AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());
        metrics.recordCall("kyc", "m", 120, 1000, 500);
        metrics.recordCall("kyc", "m", 80, 700, 300);
        metrics.recordCall("intake", "m", 50, 0, 0);
        metrics.recordDecision("APPROVED");
        metrics.recordIncident("SEV3", "AUTO_REMEDIATED");
        metrics.recordGuardrailBlock("loan");

        var summary = metrics.summary();
        assertThat(summary).containsKey("agent.execution{agent=kyc}");
        assertThat(summary.toString()).contains("agent.tokens{agent=kyc,direction=prompt}");
        assertThat(summary.toString()).contains("loan.decisions{outcome=APPROVED}");
        assertThat(summary.toString()).contains("incident.handled");
        assertThat(summary.toString()).contains("guardrail.blocks");
    }

    @Test
    void costReportUsesConfiguredPricing() {
        AgentProperties props = new AgentProperties();
        AgentProperties.Pricing price = new AgentProperties.Pricing();
        price.setInputPer1k(1.0);
        price.setOutputPer1k(2.0);
        props.getPricing().put("priced", price);
        var registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry, props);

        metrics.recordCall("kyc", "priced", 10, 1000, 500);
        metrics.recordCall("kyc", "unpriced", 10, 1000, 500);

        var report = metrics.costReport();
        assertThat(report.get("totalEstimatedUsd")).isEqualTo(2.0);
        assertThat(report.get("byAgent").toString()).contains("calls=2", "promptTokens=2000");
        assertThat(registry.get("agent.cost.usd").tag("model", "priced").counter().count()).isEqualTo(2);
        assertThat(registry.get("agent.usage.unpriced").tag("model", "unpriced").counter().count()).isEqualTo(1);
    }

    @Test
    void traceStoreKeepsMostRecentWithinCapacity() {
        ExecutionTraceStore store = new ExecutionTraceStore();
        for (int i = 0; i < 250; i++) {
            store.record("loan", "A001", "agent-" + i, 10, "PASS");
        }
        var recent = store.recent(10);
        assertThat(recent).hasSize(10);
        assertThat(recent.getFirst().agent()).isEqualTo("agent-249");
        assertThat(store.recent(500)).hasSize(200);
    }

    @Test
    void traceEntriesCarryFlowAndOutcome() {
        ExecutionTraceStore store = new ExecutionTraceStore();
        store.record("incident", "INC-1", "incident-triage", 250, "OK");
        var entry = store.recent(1).getFirst();
        assertThat(entry.flow()).isEqualTo("incident");
        assertThat(entry.correlationId()).isEqualTo("INC-1");
        assertThat(entry.durationMs()).isEqualTo(250);
    }
}
