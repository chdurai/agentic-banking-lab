package io.github.chdurai.agenticbanking.incident;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic post-remediation verification gate.
 */
@Component
public class IncidentRecoveryVerifier {

    public record Result(boolean recovered, String reason) {}

    public Result verify(Incident incident, IncidentTriage triage, DiagnosisReport diagnosis, List<String> executionLog) {
        if (incident.tags() != null && incident.tags().stream().anyMatch(t -> t.equalsIgnoreCase("force-verify-fail"))) {
            return new Result(false, "forced verification failure tag present");
        }
        if (diagnosis.confidencePct() < 70) {
            return new Result(false, "diagnosis confidence below verification floor");
        }
        if (executionLog == null || executionLog.isEmpty()) {
            return new Result(false, "no remediation action executed");
        }
        if (triage.severity() == IncidentTriage.Severity.SEV1 || triage.severity() == IncidentTriage.Severity.SEV2) {
            return new Result(false, "high-severity incidents require human verification");
        }
        return new Result(true, "recovery signals consistent with remediation");
    }
}
