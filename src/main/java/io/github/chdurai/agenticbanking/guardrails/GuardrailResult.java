package io.github.chdurai.agenticbanking.guardrails;

import io.github.chdurai.agenticbanking.model.LoanApplication;

import java.util.List;

/**
 * Outcome of input guardrail screening: a sanitized application plus any flags raised.
 * blocked=true means the request must not reach any LLM.
 * secondLine=true marks soft signals that merit a model-based or human check.
 */
public record GuardrailResult(
        LoanApplication sanitizedApplication,
        List<String> flags,
        boolean blocked,
        boolean secondLine
) {
    public GuardrailResult(LoanApplication sanitizedApplication, List<String> flags, boolean blocked) {
        this(sanitizedApplication, flags, blocked, false);
    }
}
