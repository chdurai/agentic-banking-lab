package io.github.chdurai.agenticbanking.memory;

import io.github.chdurai.agenticbanking.model.LoanDecision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Case memory: every decision is recorded (audit trail) and prior history for an
 * applicant informs future decisions (repeat applicants, velocity checks).
 */
public interface CaseMemoryStore {

    void record(LoanDecision decision);

    List<LoanDecision> historyFor(String applicantId);

    Optional<LoanDecision> latestFor(String applicantId);

    /** Compact prior-history text for prompts. */
    String summarizeHistory(String applicantId);

    /** Total applications seen across all applicants. */
    int totalCases();

    /** All recorded decisions in arrival order (for model monitoring). */
    List<LoanDecision> allDecisions();

    /** Purge all case-memory rows for one subject reference (erasure request). */
    int eraseSubject(String subjectRef);

    /** Purge expired entries; returns the number removed. */
    int purgeExpired(Instant now);
}
