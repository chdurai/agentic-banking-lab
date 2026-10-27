package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Emits one breach event per unresolved grievance whose SLA due time has elapsed. */
@Component
public class GrievanceSlaMonitor {

    private final GrievanceMemoryStore memory;
    private final PlatformEventPublisher events;
    private final AgentMetrics metrics;

    public GrievanceSlaMonitor(GrievanceMemoryStore memory, PlatformEventPublisher events, AgentMetrics metrics) {
        this.memory = memory;
        this.events = events;
        this.metrics = metrics;
    }

    public void sweep() {
        sweep(Instant.now());
    }

    public int sweep(Instant now) {
        int breaches = 0;
        for (GrievanceMemoryStore.SlaDeadline d : memory.overdue(now)) {
            events.publish(PlatformEvent.of("grievance.sla.breached", "grievance", d.grievanceId(),
                    "SLA breached for " + d.route() + " grievance due at " + d.dueAt()));
            metrics.recordCounter("grievance.sla.breached", "route", d.route().name());
            memory.markBreachPublished(d.grievanceId());
            breaches++;
        }
        return breaches;
    }
}
