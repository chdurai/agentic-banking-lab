package io.github.chdurai.agenticbanking.eval;

import io.github.chdurai.agenticbanking.model.LoanApplication;
import io.github.chdurai.agenticbanking.model.LoanDecision;

public record GoldenCase(
        String id,
        String description,
        LoanApplication application,
        LoanDecision.Decision expectedDecision
) {}
