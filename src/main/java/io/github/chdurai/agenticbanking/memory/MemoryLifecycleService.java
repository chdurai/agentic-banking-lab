package io.github.chdurai.agenticbanking.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Retention and erasure helpers for memory tiers.
 */
@Component
public class MemoryLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(MemoryLifecycleService.class);

    private final CaseMemoryStore caseMemory;
    private final EpisodicMemoryStore episodic;

    public MemoryLifecycleService(CaseMemoryStore caseMemory, EpisodicMemoryStore episodic) {
        this.caseMemory = caseMemory;
        this.episodic = episodic;
    }

    public record ErasureResult(int caseRowsDeleted, int episodicRowsDeleted) {
        public int total() {
            return caseRowsDeleted + episodicRowsDeleted;
        }
    }

    public ErasureResult eraseSubject(String subjectRef) {
        return new ErasureResult(caseMemory.eraseSubject(subjectRef), episodic.eraseSubject(subjectRef));
    }

    public void purgeExpired() {
        Instant now = Instant.now();
        int caseRows = caseMemory.purgeExpired(now);
        int episodicRows = episodic.purgeExpired(now);
        if (caseRows + episodicRows > 0) {
            log.info("[memory] purged expired rows: case={}, episodic={}", caseRows, episodicRows);
        }
    }
}
