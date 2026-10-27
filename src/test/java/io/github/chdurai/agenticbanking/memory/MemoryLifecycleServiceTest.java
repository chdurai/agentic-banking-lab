package io.github.chdurai.agenticbanking.memory;

import io.github.chdurai.agenticbanking.model.LoanDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryLifecycleServiceTest {

    @Test
    void erasurePurgesAcrossCaseAndEpisodicTiers() {
        InMemoryCaseMemoryStore caseStore = new InMemoryCaseMemoryStore();
        EpisodicMemoryStore episodic = new EpisodicMemoryStore();
        MemoryLifecycleService lifecycle = new MemoryLifecycleService(caseStore, episodic);

        caseStore.record(new LoanDecision("sub-x", LoanDecision.Decision.APPROVED, new BigDecimal("1000"),
                new BigDecimal("7.0"), List.of(), List.of(), "test", List.of(), 0, Instant.now()));
        episodic.recordGrievancePattern("sub-x", "PRJ-1", "PROJECT_GRM_OFFICER", "summary");
        episodic.recordGrievancePattern("sub-y", "PRJ-1", "PROJECT_GRM_OFFICER", "other");

        MemoryLifecycleService.ErasureResult result = lifecycle.eraseSubject("sub-x");

        assertThat(result.caseRowsDeleted()).isEqualTo(1);
        assertThat(result.episodicRowsDeleted()).isEqualTo(1);
        assertThat(caseStore.historyFor("sub-x")).isEmpty();
        assertThat(episodic.total()).isEqualTo(1);
    }
}
