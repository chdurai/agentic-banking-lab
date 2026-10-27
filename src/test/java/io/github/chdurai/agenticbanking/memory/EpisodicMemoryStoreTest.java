package io.github.chdurai.agenticbanking.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EpisodicMemoryStoreTest {

    private final EpisodicMemoryStore store =
            new EpisodicMemoryStore(new RetentionProperties(2555, 30, 7, 14), new MemorySanitizer());

    @Test
    void incidentRetrievalIsScopedByServiceAndSymptom() {
        store.recordIncident("sub-a", "loan-service", "db timeout", "restart pod on node 3", true);
        store.recordIncident("sub-b", "payments-service", "db timeout", "failover applied", true);

        assertThat(store.similarIncidents("loan-service", "db timeout", 10))
                .hasSize(1)
                .allMatch(e -> e.subjectRef().equals("sub-a"));
    }

    @Test
    void unverifiedIncidentNeverEntersEpisodicMemory() {
        store.recordIncident("sub-a", "loan-service", "latency spike", "investigate", false);
        assertThat(store.similarIncidents("loan-service", "latency spike", 10)).isEmpty();
    }

    @Test
    void sanitizerRedactsSensitivePatterns() {
        store.recordGrievancePattern("sub-g", "PRJ-7", "PROJECT_GRM_OFFICER",
                "Call me at 555-123-4567 or email x@y.org, card 4111 1111 1111 1111");

        String summary = store.grievancePatterns("PRJ-7", "PROJECT_GRM_OFFICER", 1).getFirst().summary();
        assertThat(summary).contains("<redacted-phone>").contains("<redacted-email>").contains("<redacted-card>");
        assertThat(summary).doesNotContain("555-123-4567").doesNotContain("x@y.org").doesNotContain("4111 1111");
    }

    @Test
    void erasurePurgesOnlyTargetSubject() {
        store.recordGrievancePattern("sub-a", "PRJ-1", "PROJECT_GRM_OFFICER", "summary A");
        store.recordGrievancePattern("sub-b", "PRJ-1", "PROJECT_GRM_OFFICER", "summary B");

        assertThat(store.eraseSubject("sub-a")).isEqualTo(1);
        assertThat(store.grievancePatterns("PRJ-1", "PROJECT_GRM_OFFICER", 10))
                .hasSize(1)
                .allMatch(e -> e.subjectRef().equals("sub-b"));
    }

    @Test
    void purgeDropsExpiredEpisodicRows() {
        EpisodicMemoryStore shortRetention =
                new EpisodicMemoryStore(new RetentionProperties(2555, 1, 1, 14), new MemorySanitizer());
        shortRetention.recordGrievancePattern("sub-a", "PRJ-1", "PROJECT_GRM_OFFICER", "summary");
        assertThat(shortRetention.total()).isEqualTo(1);
        shortRetention.purgeExpired(Instant.now().plus(2, java.time.temporal.ChronoUnit.DAYS));
        assertThat(shortRetention.total()).isZero();
    }
}
