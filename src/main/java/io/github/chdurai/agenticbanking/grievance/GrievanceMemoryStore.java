package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.ops.BoundedHistory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded operational history with a shared minimal insights projection in JDBC mode. */
@Component
public class GrievanceMemoryStore {

    private final Optional<JdbcGrievanceInsightsSource> insightsSource;
    private final BoundedHistory<GrievanceResolution> history = new BoundedHistory<>(500);
    private final BoundedHistory<GrievanceClosure> closures = new BoundedHistory<>(500);
    private final Map<String, GrievanceResolution> latestById = new HashMap<>();
    private final Map<String, GrievanceClosure> closureById = new HashMap<>();
    private final Map<String, SlaDeadline> slaDeadlines = new HashMap<>();
    private final Set<String> breachPublished = new HashSet<>();

    public GrievanceMemoryStore() {
        this(Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GrievanceMemoryStore(Optional<JdbcGrievanceInsightsSource> insightsSource) {
        this.insightsSource = insightsSource;
    }

    Optional<JdbcGrievanceInsightsSource> insightsSource() {
        return insightsSource;
    }

    public synchronized void record(GrievanceResolution resolution) {
        insightsSource.ifPresent(source -> source.record(resolution));
        history.add(resolution);
        latestById.put(io.github.chdurai.agenticbanking.security.CallerContext.key(resolution.grievanceId()), resolution);
        if (tracksSla(resolution)) {
            slaDeadlines.put(io.github.chdurai.agenticbanking.security.CallerContext.key(resolution.grievanceId()), new SlaDeadline(
                    resolution.grievanceId(),
                    resolution.projectId(),
                    resolution.status(),
                    resolution.route(),
                    resolution.handledAt().plusSeconds(Math.max(0L, resolution.slaHours()) * 3600L)));
        } else {
            slaDeadlines.remove(io.github.chdurai.agenticbanking.security.CallerContext.key(resolution.grievanceId()));
            breachPublished.remove(io.github.chdurai.agenticbanking.security.CallerContext.key(resolution.grievanceId()));
        }
    }

    public synchronized void recordClosure(GrievanceClosure closure) {
        closures.add(closure);
        closureById.put(io.github.chdurai.agenticbanking.security.CallerContext.key(closure.grievanceId()), closure);
        slaDeadlines.remove(io.github.chdurai.agenticbanking.security.CallerContext.key(closure.grievanceId()));
        breachPublished.remove(io.github.chdurai.agenticbanking.security.CallerContext.key(closure.grievanceId()));
    }

    public synchronized Optional<GrievanceResolution> find(String grievanceId) {
        return Optional.ofNullable(latestById.get(io.github.chdurai.agenticbanking.security.CallerContext.key(grievanceId)));
    }

    public synchronized Optional<GrievanceClosure> closure(String grievanceId) {
        return Optional.ofNullable(closureById.get(io.github.chdurai.agenticbanking.security.CallerContext.key(grievanceId)));
    }

    public synchronized List<GrievanceClosure> recentClosures(int limit) {
        return closures.recent(limit);
    }

    public synchronized List<SlaDeadline> overdue(Instant now) {
        return slaDeadlines.entrySet().stream()
                .filter(e -> e.getKey().startsWith(io.github.chdurai.agenticbanking.security.CallerContext.key("")))
                .map(Map.Entry::getValue)
                .filter(d -> !now.isBefore(d.dueAt()))
                .filter(d -> !breachPublished.contains(io.github.chdurai.agenticbanking.security.CallerContext.key(d.grievanceId())))
                .toList();
    }

    public synchronized void markBreachPublished(String grievanceId) {
        breachPublished.add(io.github.chdurai.agenticbanking.security.CallerContext.key(grievanceId));
    }

    public List<GrievanceResolution> recent(int limit) {
        return history.recent(limit);
    }

    public int total() {
        return history.size();
    }

    private static boolean tracksSla(GrievanceResolution resolution) {
        return resolution.slaHours() > 0
                && resolution.status() != GrievanceResolution.Status.BLOCKED;
    }

    public record SlaDeadline(
            String grievanceId,
            String projectId,
            GrievanceResolution.Status status,
            GrievanceResolution.Route route,
            Instant dueAt
    ) {
    }
}
