package io.github.chdurai.agenticbanking.grievance;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GrievanceSlaMonitorTest {

    @Test
    void emitsSingleBreachEventForOverdueUnclosedGrievance() {
        GrievanceMemoryStore memory = new GrievanceMemoryStore();
        List<PlatformEvent> events = new ArrayList<>();
        GrievanceSlaMonitor monitor = new GrievanceSlaMonitor(memory,
                new PlatformEventPublisher(e -> events.add((PlatformEvent) e)),
                new AgentMetrics(new SimpleMeterRegistry()));
        Instant opened = Instant.parse("2026-01-01T00:00:00Z");
        memory.record(new GrievanceResolution("GRV-1", "PRJ-1", GrievanceResolution.Status.PENDING_HUMAN_REVIEW,
                GrievanceResolution.Route.SAFEGUARDS_SPECIALIST, 24, null, null, List.of(), "n", "a1", opened));

        assertThat(monitor.sweep(opened.plusSeconds(25 * 3600L))).isEqualTo(1);
        assertThat(monitor.sweep(opened.plusSeconds(26 * 3600L))).isZero();
        assertThat(events).extracting(PlatformEvent::type).containsExactly("grievance.sla.breached");
    }

    @Test
    void closedGrievanceDoesNotBreach() {
        GrievanceMemoryStore memory = new GrievanceMemoryStore();
        List<PlatformEvent> events = new ArrayList<>();
        GrievanceSlaMonitor monitor = new GrievanceSlaMonitor(memory,
                new PlatformEventPublisher(e -> events.add((PlatformEvent) e)),
                new AgentMetrics(new SimpleMeterRegistry()));
        Instant opened = Instant.parse("2026-01-01T00:00:00Z");
        memory.record(new GrievanceResolution("GRV-2", "PRJ-2", GrievanceResolution.Status.ROUTED,
                GrievanceResolution.Route.PROJECT_GRM_OFFICER, 24, null, null, List.of(), "n", null, opened));
        memory.recordClosure(new GrievanceClosure("GRV-2", "PRJ-2", GrievanceFinalClassification.SUBSTANTIATED,
                "officer-1", "closed", opened.plusSeconds(3600), null));

        assertThat(monitor.sweep(opened.plusSeconds(30 * 3600L))).isZero();
        assertThat(events).isEmpty();
    }
}
