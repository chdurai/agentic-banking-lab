package io.github.chdurai.agenticbanking.eval;

import io.github.chdurai.agenticbanking.model.LoanDecision;

import java.util.List;

public record EvaluationResult(
        int total,
        int exactMatches,
        int acceptableMatches,
        double accuracy,
        List<CaseOutcome> cases
) {
    public record CaseOutcome(
            String caseId,
            String description,
            LoanDecision.Decision expected,
            LoanDecision.Decision actual,
            boolean exactMatch,
            boolean acceptable,
            int revisionCount
    ) {}
}
