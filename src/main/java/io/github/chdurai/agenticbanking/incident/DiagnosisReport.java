package io.github.chdurai.agenticbanking.incident;

import java.util.List;

/**
 * Diagnosis output grounded in retrieved runbook sections.
 */
public record DiagnosisReport(
        List<String> probableCauses,
        List<String> evidence,
        List<String> matchedRunbooks,
        int confidencePct
) {}
