package io.github.chdurai.agenticbanking.memory;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sanitized episodic memory for resolved incidents and grievance patterns.
 *
 * <p>Only workflow code writes here. Entries are keyed by service/symptom (incidents) or
 * project route (grievances) and expire per retention policy. Cross-subject retrieval is
 * intentionally narrow and never supports "similar applicants".</p>
 */
@Component
public class EpisodicMemoryStore {

    public enum Kind { INCIDENT, GRIEVANCE }

    public record Entry(String id, Kind kind, String subjectRef, String key, String summary,
                        boolean verified, Instant createdAt, Instant expiresAt) {}

    private static final int CAPACITY = 2000;
    private record ScopedEntry(String tenant, Entry entry) {}
    private final ConcurrentLinkedDeque<ScopedEntry> entries = new ConcurrentLinkedDeque<>();
    private final RetentionProperties retention;
    private final MemorySanitizer sanitizer;

    public EpisodicMemoryStore(RetentionProperties retention, MemorySanitizer sanitizer) {
        this.retention = retention;
        this.sanitizer = sanitizer;
    }

    public EpisodicMemoryStore() {
        this(RetentionProperties.defaults(), new MemorySanitizer());
    }

    public void recordIncident(String subjectRef, String service, String symptomKey, String summary, boolean verified) {
        if (!verified) {
            return;
        }
        put(new Entry(UUID.randomUUID().toString(), Kind.INCIDENT, subjectRef,
                incidentKey(service, symptomKey), sanitizer.sanitizeSummary(summary), true, Instant.now(),
                Instant.now().plus(retention.episodicDays(), ChronoUnit.DAYS)));
    }

    public void recordGrievancePattern(String subjectRef, String projectId, String route, String summary) {
        put(new Entry(UUID.randomUUID().toString(), Kind.GRIEVANCE, subjectRef,
                grievanceKey(projectId, route), sanitizer.sanitizeSummary(summary), true, Instant.now(),
                Instant.now().plus(retention.sensitiveDays(), ChronoUnit.DAYS)));
    }

    public List<Entry> similarIncidents(String service, String symptomKey, int limit) {
        String key = incidentKey(service, symptomKey);
        return entries.descendingIterator().hasNext()
                ? collect(limit, e -> e.kind() == Kind.INCIDENT && e.verified() && e.key().equals(key))
                : List.of();
    }

    public List<Entry> grievancePatterns(String projectId, String route, int limit) {
        String key = grievanceKey(projectId, route);
        return entries.descendingIterator().hasNext()
                ? collect(limit, e -> e.kind() == Kind.GRIEVANCE && e.key().equals(key))
                : List.of();
    }

    public int eraseSubject(String subjectRef) {
        int removed = 0;
        for (ScopedEntry row : List.copyOf(entries)) {
            if (visible(row) && row.entry().subjectRef().equals(subjectRef) && entries.remove(row)) {
                removed++;
            }
        }
        return removed;
    }

    public int purgeExpired(Instant now) {
        int removed = 0;
        for (ScopedEntry row : List.copyOf(entries)) {
            if (visible(row) && !row.entry().expiresAt().isAfter(now) && entries.remove(row)) {
                removed++;
            }
        }
        return removed;
    }

    public int total() {
        return (int) entries.stream().filter(EpisodicMemoryStore::visible).count();
    }

    private synchronized void put(Entry e) {
        entries.addLast(new ScopedEntry(io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId(), e));
        while (total() > CAPACITY) {
            var oldest = entries.stream().filter(EpisodicMemoryStore::visible).findFirst().orElseThrow();
            entries.remove(oldest);
        }
    }

    private List<Entry> collect(int limit, java.util.function.Predicate<Entry> include) {
        List<Entry> out = new ArrayList<>();
        var it = entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            ScopedEntry row = it.next();
            Entry e = row.entry();
            if (visible(row) && e.expiresAt().isAfter(Instant.now()) && include.test(e)) {
                out.add(e);
            }

        }
        return out;
    }

    private static boolean visible(ScopedEntry row) {
        return row.tenant().equals(io.github.chdurai.agenticbanking.security.CallerContext.current().tenantId());
    }

    public static String incidentKey(String service, String symptomKey) {
        return "incident|" + norm(service) + "|" + norm(symptomKey);
    }

    public static String grievanceKey(String projectId, String route) {
        return "grievance|" + norm(projectId) + "|" + norm(route);
    }

    private static String norm(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }
}
