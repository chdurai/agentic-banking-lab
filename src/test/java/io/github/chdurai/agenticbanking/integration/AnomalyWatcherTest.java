package io.github.chdurai.agenticbanking.integration;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.chdurai.agenticbanking.incident.IncidentMemoryStore;
import io.github.chdurai.agenticbanking.incident.IncidentResolution;
import io.github.chdurai.agenticbanking.incident.AlertIngestionService;
import io.github.chdurai.agenticbanking.ops.AnomalyWatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.ai.model.chat=none")
@Import(AgentFlowsStubbedTest.StubConfig.class)
class AnomalyWatcherTest {

    private static final AtomicInteger N = new AtomicInteger();

    @Autowired StubChatModel llm;
    @Autowired MeterRegistry registry;
    @Autowired AnomalyWatcher watcher;
    @Autowired AlertIngestionService alerts;
    @Autowired IncidentMemoryStore incidents;

    private String uri;

    @BeforeEach
    void reset() {
        llm.reset();
        uri = "/api/test-" + N.incrementAndGet() + "/call";
    }

    private void record(int ok, int serverErrors) {
        for (int i = 0; i < ok; i++) {
            timer("200").record(Duration.ofMillis(20));
        }
        for (int i = 0; i < serverErrors; i++) {
            timer("500").record(Duration.ofMillis(40));
        }
    }

    private Timer timer(String status) {
        return Timer.builder("http.server.requests")
                .tags("uri", uri, "method", "GET", "status", status, "exception", "none", "outcome",
                        status.startsWith("5") ? "SERVER_ERROR" : "SUCCESS")
                .register(registry);
    }

    private List<IncidentResolution> autoDetected() {
        return incidents.recent(50).stream().filter(r -> r.incidentId().startsWith("ALERT-")).toList();
    }

    @Test
    void errorRateBreachOpensIncidentThroughFullAgentPipeline() {
        int before = autoDetected().size();
        record(20, 0);
        watcher.check();           // baseline with existing traffic, must not alert
        assertThat(autoDetected()).hasSize(before);

        record(20, 20);            // 50% 5xx over 40 fresh samples
        watcher.check();
        alerts.drain();

        assertThat(autoDetected()).hasSize(before + 1);
        assertThat(llm.callsTo("incident-triage")).isEqualTo(1);
        assertThat(llm.callsTo("incident-diagnosis")).isEqualTo(1);
    }

    @Test
    void cooldownPreventsAlertStormsAndBaselineSuppressesFirstSample() {
        int before = autoDetected().size();
        record(20, 20);
        watcher.check();           // first sighting of a hot endpoint = baseline only
        assertThat(autoDetected()).hasSize(before);

        watcher.check();           // no new traffic -> no alert
        record(10, 10);
        watcher.check();           // breach, but within cooldown of the previous test's alert? no: fresh URI
        alerts.drain();
        assertThat(autoDetected()).hasSize(before + 1);

        record(10, 10);
        watcher.check();           // same URI breach again -> cooldown suppresses
        alerts.drain();
        assertThat(autoDetected()).hasSize(before + 1);
    }

    @Test
    void healthyTrafficAndLowVolumeDoNotAlert() {
        int before = autoDetected().size();
        watcher.check();
        record(100, 2);            // 2% error rate, below threshold
        watcher.check();
        assertThat(autoDetected()).hasSize(before);

        record(1, 1);              // 100% errors but below min-samples
        watcher.check();
        assertThat(autoDetected()).hasSize(before);
    }
}
