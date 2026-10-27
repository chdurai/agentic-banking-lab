package io.github.chdurai.agenticbanking.incident;

import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AlertIngestionServiceTest {

    private static AlertSignal alert(String source) {
        return new AlertSignal("5xx spike", "errors elevated", "loan-service",
                "production", source, "http_5xx_ratio", List.of("availability"), Instant.now());
    }

    @Test
    void alertIdsRemainUniqueAcrossServiceRestarts() {
        AlertIngestionProperties props = new AlertIngestionProperties(true, 100, 20, 10, 5000);
        IncidentOrchestrator orchestrator = mock(IncidentOrchestrator.class);
        PlatformEventPublisher events = mock(PlatformEventPublisher.class);
        AgentMetrics metrics = mock(AgentMetrics.class);

        AlertIngestionService first = new AlertIngestionService(props, orchestrator, events, metrics);
        AlertIngestionService second = new AlertIngestionService(props, orchestrator, events, metrics);

        String id1 = first.ingest(alert("pagerduty")).alertId();
        String id2 = second.ingest(alert("opsgenie")).alertId();

        assertThat(id1).isNotBlank();
        assertThat(id2).isNotBlank();
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void overloadRejectsNewAlertWithoutEvictingAcceptedWork() {
        var props = new AlertIngestionProperties(true, 1, 20, 10, 5000);
        var orchestrator = mock(IncidentOrchestrator.class);
        var service = new AlertIngestionService(props, orchestrator, mock(PlatformEventPublisher.class), mock(AgentMetrics.class));
        var first = service.ingest(alert("pagerduty"));
        assertThatThrownBy(() -> service.ingest(alert("opsgenie")))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(429));
        service.drain();
        verify(orchestrator).handle(argThat(incident -> incident.incidentId().equals("ALERT-" + first.alertId())));
        verifyNoMoreInteractions(orchestrator);
    }
}
