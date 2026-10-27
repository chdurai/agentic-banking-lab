package io.github.chdurai.agenticbanking.memory;

import io.github.chdurai.agenticbanking.model.LoanDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaseMemoryStoreTest {

    private final InMemoryCaseMemoryStore store = new InMemoryCaseMemoryStore();

    private LoanDecision decision(String applicantId, LoanDecision.Decision d) {
        return new LoanDecision(applicantId, d, new BigDecimal("10000"),
                new BigDecimal("7.5"), List.of(), List.of("test reason"),
                "test", List.of(), 0, Instant.now());
    }

    @Test
    void recordsAndRetrievesHistory() {
        store.record(decision("A001", LoanDecision.Decision.DECLINED));
        store.record(decision("A001", LoanDecision.Decision.APPROVED));
        store.record(decision("A002", LoanDecision.Decision.APPROVED));

        assertThat(store.historyFor("A001")).hasSize(2);
        assertThat(store.latestFor("A001")).get()
                .extracting(LoanDecision::decision)
                .isEqualTo(LoanDecision.Decision.APPROVED);
        assertThat(store.totalCases()).isEqualTo(3);
    }

    @Test
    void unknownApplicantHasEmptyHistory() {
        assertThat(store.historyFor("NOPE")).isEmpty();
        assertThat(store.latestFor("NOPE")).isEmpty();
    }

    @Test
    void historySummaryMentionsPriorDecisions() {
        store.record(decision("A001", LoanDecision.Decision.DECLINED));
        String summary = store.summarizeHistory("A001");
        assertThat(summary).contains("Prior applications: 1").contains("DECLINED");
        assertThat(store.summarizeHistory("NOPE")).contains("No prior applications");
    }

    @Test
    void erasurePurgesOnlyTargetSubject() {
        store.record(decision("A001", LoanDecision.Decision.DECLINED));
        store.record(decision("A002", LoanDecision.Decision.APPROVED));

        assertThat(store.eraseSubject("A001")).isEqualTo(1);
        assertThat(store.historyFor("A001")).isEmpty();
        assertThat(store.historyFor("A002")).hasSize(1);
    }

    @Test
    void retentionPurgeDropsExpiredRows() {
        InMemoryCaseMemoryStore shortRetention = new InMemoryCaseMemoryStore(new RetentionProperties(1, 90, 30, 14));
        shortRetention.record(decision("A001", LoanDecision.Decision.APPROVED));
        assertThat(shortRetention.totalCases()).isEqualTo(1);
        shortRetention.purgeExpired(Instant.now().plusSeconds(172800));
        assertThat(shortRetention.totalCases()).isZero();
    }
}
