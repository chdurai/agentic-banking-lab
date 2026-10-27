package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.approval.ApprovalOutcomeListener;
import io.github.chdurai.agenticbanking.approval.ApprovalRequest;
import io.github.chdurai.agenticbanking.ops.AgentMetrics;
import io.github.chdurai.agenticbanking.ops.PlatformEvent;
import io.github.chdurai.agenticbanking.ops.PlatformEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Captures officer ground-truth closure for grievance approvals.
 * This closes SLA tracking and emits a final grievance event.
 */
@Component
public class GrievanceApprovalOutcomeListener implements ApprovalOutcomeListener {

    private final GrievanceMemoryStore memory;
    private final PlatformEventPublisher events;
    private final AgentMetrics metrics;

    public GrievanceApprovalOutcomeListener(GrievanceMemoryStore memory,
                                            PlatformEventPublisher events,
                                            AgentMetrics metrics) {
        this.memory = memory;
        this.events = events;
        this.metrics = metrics;
    }

    @Override
    public void onOutcome(ApprovalRequest r) {
        if (!"grievance".equals(r.flow())) {
            return;
        }
        GrievanceFinalClassification classification = switch (r.status()) {
            case APPROVED, OVERRIDDEN -> GrievanceFinalClassification.SUBSTANTIATED;
            case REJECTED -> GrievanceFinalClassification.UNSUBSTANTIATED;
            case EXPIRED -> GrievanceFinalClassification.EXPIRED_UNRESOLVED;
            default -> null;
        };
        if (classification == null) {
            return;
        }
        if (memory.closure(r.subjectId()).isPresent()) {
            return;
        }
        String projectId = memory.find(r.subjectId())
                .map(GrievanceResolution::projectId)
                .orElseThrow(() -> new IllegalStateException("No grievance record for " + r.subjectId()));
        GrievanceClosure closure = new GrievanceClosure(
                r.subjectId(), projectId, classification, r.decidedBy(), r.note(), Instant.now(), r.id());
        memory.recordClosure(closure);
        metrics.recordCounter("grievance.closed", "classification", classification.name());
        events.publish(PlatformEvent.of("grievance.closed", "grievance", r.subjectId(),
                classification + " by " + r.decidedBy()));
    }
}
