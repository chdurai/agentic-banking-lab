package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.security.CallerContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "platform.persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryGrievanceInsightsPublicationStore implements GrievanceInsightsPublicationStore {
    private record Key(String tenant, LocalDate week) {}
    private final Map<Key, GrievanceInsightsAgent.WeeklyInsights> snapshots = new HashMap<>();

    @Override public synchronized Optional<GrievanceInsightsAgent.WeeklyInsights> find(LocalDate weekStarting) {
        return Optional.ofNullable(snapshots.get(new Key(CallerContext.current().tenantId(), weekStarting)));
    }

    @Override public synchronized GrievanceInsightsAgent.WeeklyInsights publish(GrievanceInsightsAgent.WeeklyInsights candidate) {
        return snapshots.computeIfAbsent(new Key(CallerContext.current().tenantId(), candidate.weekStarting()), key -> candidate);
    }
}
