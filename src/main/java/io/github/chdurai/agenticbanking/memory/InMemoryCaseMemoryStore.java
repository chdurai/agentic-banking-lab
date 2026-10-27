package io.github.chdurai.agenticbanking.memory;

import io.github.chdurai.agenticbanking.model.LoanDecision;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation for the demo. Production: back this with Postgres
 * (decisions are a regulatory audit trail - retention typically 25 months+ for ECOA).
 */
@Component
public class InMemoryCaseMemoryStore implements CaseMemoryStore {

    private record MemoryRow(LoanDecision decision, Instant expiresAt) {}

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MemoryRow>> byApplicant =
            new ConcurrentHashMap<>();
    private final RetentionProperties retention;

    public InMemoryCaseMemoryStore(RetentionProperties retention) {
        this.retention = retention;
    }

    public InMemoryCaseMemoryStore() {
        this(RetentionProperties.defaults());
    }

    @Override
    public void record(LoanDecision decision) {
        purgeExpired(Instant.now());
        Instant expiresAt = Instant.now().plus(retention.caseDays(), ChronoUnit.DAYS);
        byApplicant.computeIfAbsent(io.github.chdurai.agenticbanking.security.CallerContext.key(decision.applicationId()), k -> new CopyOnWriteArrayList<>())
                .add(new MemoryRow(decision, expiresAt));
    }

    @Override
    public List<LoanDecision> historyFor(String applicantId) {
        purgeExpired(Instant.now());
        return byApplicant.getOrDefault(io.github.chdurai.agenticbanking.security.CallerContext.key(applicantId), new CopyOnWriteArrayList<>())
                .stream().map(MemoryRow::decision).toList();
    }

    @Override
    public Optional<LoanDecision> latestFor(String applicantId) {
        List<LoanDecision> history = historyFor(applicantId);
        return history.isEmpty() ? Optional.empty() : Optional.of(history.getLast());
    }

    @Override
    public int totalCases() {
        purgeExpired(Instant.now());
        return allDecisions().size();
    }

    @Override
    public List<LoanDecision> allDecisions() {
        purgeExpired(Instant.now());
        return byApplicant.entrySet().stream()
                .filter(e -> e.getKey().startsWith(io.github.chdurai.agenticbanking.security.CallerContext.key("")))
                .flatMap(e -> e.getValue().stream())
                .map(MemoryRow::decision)
                .sorted(java.util.Comparator.comparing(LoanDecision::decidedAt)).toList();
    }

    /** Human-readable prior history for inclusion in prompts. */
    @Override
    public String summarizeHistory(String applicantId) {
        List<LoanDecision> history = historyFor(applicantId);
        if (history.isEmpty()) {
            return "No prior applications on file for this applicant.";
        }
        StringBuilder sb = new StringBuilder("Prior applications: " + history.size() + ". ");
        for (LoanDecision d : history) {
            sb.append("[").append(d.decidedAt()).append("] ").append(d.decision());
            if (d.adverseActionReasons() != null && !d.adverseActionReasons().isEmpty()) {
                sb.append(" (reasons: ").append(String.join("; ", d.adverseActionReasons())).append(")");
            }
            sb.append(' ');
        }
        return sb.toString();
    }

    @Override
    public int eraseSubject(String subjectRef) {
        CopyOnWriteArrayList<MemoryRow> removed = byApplicant.remove(io.github.chdurai.agenticbanking.security.CallerContext.key(subjectRef));
        return removed == null ? 0 : removed.size();
    }

    @Override
    public int purgeExpired(Instant now) {
        int removed = 0;
        for (String subject : new ArrayList<>(byApplicant.keySet())) {
            if (!subject.startsWith(io.github.chdurai.agenticbanking.security.CallerContext.key(""))) continue;
            CopyOnWriteArrayList<MemoryRow> rows = byApplicant.get(subject);
            if (rows == null) {
                continue;
            }
            int before = rows.size();
            rows.removeIf(r -> r.expiresAt().isBefore(now));
            removed += before - rows.size();
            if (rows.isEmpty()) {
                byApplicant.remove(subject, rows);
            }
        }
        return removed;
    }
}
