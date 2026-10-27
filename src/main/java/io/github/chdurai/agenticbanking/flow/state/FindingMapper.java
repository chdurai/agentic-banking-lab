package io.github.chdurai.agenticbanking.flow.state;

import io.github.chdurai.agenticbanking.guardrails.InputGuardrail;
import io.github.chdurai.agenticbanking.model.AgentReport;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Adapter between the legacy {@link AgentReport} API and {@link Finding}; the round trip is lossless. */
@Component
public class FindingMapper {

    private final InputGuardrail guardrail;

    public FindingMapper(InputGuardrail guardrail) {
        this.guardrail = guardrail;
    }

    public Finding toFinding(String caseId, String flow, AgentReport r, Provenance provenance) {
        List<String> redFlags = r.redFlags() == null ? List.of() : r.redFlags();
        List<String> observations = r.findings() == null ? List.of() : r.findings();
        List<RiskSignal> signals = new ArrayList<>();
        for (int i = 0; i < redFlags.size(); i++) {
            signals.add(new RiskSignal(slug(redFlags.get(i), i), redFlags.get(i), 0, r.agentName()));
        }
        boolean suspect = suspicious(r.summary()) || observations.stream().anyMatch(this::suspicious)
                || redFlags.stream().anyMatch(this::suspicious);
        return new Finding(Ids.uuidV7(), caseId, flow, r.agentName(),
                r.status() == AgentReport.Status.UNAVAILABLE ? Finding.Kind.UNAVAILABLE : Finding.Kind.ASSESSMENT,
                Finding.Status.valueOf(r.status().name()), Map.of("riskScore", r.riskScore()), signals,
                r.summary(), observations, List.of(), null, null, provenance, suspect,
                Finding.SCHEMA_VERSION, Instant.now());
    }

    public AgentReport toReport(Finding f) {
        Object score = f.facts().get("riskScore");
        return new AgentReport(f.agentId(), AgentReport.Status.valueOf(f.status().name()),
                score instanceof Number n ? n.intValue() : 0, f.narrative(), f.observations(),
                f.riskSignals().stream().map(RiskSignal::description).toList());
    }

    private boolean suspicious(String text) {
        return text != null && guardrail.screenText("finding", text, 4000).blocked();
    }

    private static String slug(String text, int index) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        s = s.length() > 40 ? s.substring(0, 40) : s;
        return (s.isEmpty() ? "flag" : s) + "_" + index;
    }
}
