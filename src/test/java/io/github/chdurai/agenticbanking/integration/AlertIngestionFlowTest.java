package io.github.chdurai.agenticbanking.integration;

import io.github.chdurai.agenticbanking.incident.AlertIngestionService;
import io.github.chdurai.agenticbanking.incident.AlertSignal;
import io.github.chdurai.agenticbanking.incident.IncidentMemoryStore;
import io.github.chdurai.agenticbanking.incident.IncidentResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(AgentFlowsStubbedTest.StubConfig.class)
class AlertIngestionFlowTest {

    private static final AtomicInteger N = new AtomicInteger();

    @Autowired StubChatModel llm;
    @Autowired AlertIngestionService alerts;
    @Autowired IncidentMemoryStore incidents;

    @BeforeEach
    void reset() {
        llm.reset();
    }

    private static AlertSignal signal(String service, String source) {
        int n = N.incrementAndGet();
        return new AlertSignal("5xx spike " + n,
                "error rate increased on " + service,
                service, "production", source, "http_5xx_ratio",
                List.of("availability"), Instant.now());
    }

    private List<IncidentResolution> alertIncidents() {
        return incidents.recent(200).stream().filter(r -> r.incidentId().startsWith("ALERT-")).toList();
    }

    @Test
    void webhookAlertIsQueuedAndProcessedByWorker() {
        int before = alertIncidents().size();
        var receipt = alerts.ingest(signal("loan-service", "pagerduty"));
        assertThat(receipt.accepted()).isTrue();

        alerts.drain();

        assertThat(alertIncidents()).hasSize(before + 1);
    }

    @Test
    void selfWatchSignalsAreIgnored() {
        int before = alertIncidents().size();
        var receipt = alerts.ingest(signal("agentic-banking", "agentic-banking.monitor"));
        assertThat(receipt.ignored()).isTrue();

        alerts.drain();

        assertThat(alertIncidents()).hasSize(before);
    }

    @Test
    void duplicateFingerprintWithinWindowDoesNotCreateStormOnOutage() {
        llm.failingAgents.add("incident-triage");
        int before = alertIncidents().size();
        AlertSignal s = signal("api-gateway", "pagerduty");
        alerts.ingest(s);
        alerts.ingest(s);
        alerts.ingest(s);

        alerts.drain();

        assertThat(alertIncidents()).hasSize(before + 1);
    }
}
