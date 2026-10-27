package io.github.chdurai.agenticbanking.incident;

import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Incident agent 1: NLP triage - classifies the incident category, assigns
 * severity, detects reporter sentiment, and summarizes the ticket.
 */
@Component
public class IncidentTriageAgent {

    private static final Logger log = LoggerFactory.getLogger(IncidentTriageAgent.class);
    static final String NAME = "incident-triage";

    private static final String SYSTEM = """
            You are an IT incident triage specialist. Given a raw incident report:
            1. category: NETWORK, DATABASE, APPLICATION, SECURITY, ACCESS, INFRASTRUCTURE, or OTHER.
            2. severity: SEV1 = customer-facing outage or active breach; SEV2 = major degradation
               or security incident contained; SEV3 = partial impact, workaround exists;
               SEV4 = minor/cosmetic.
            3. sentiment: detect the reporter's emotional tone (CALM, CONCERNED, FRUSTRATED, URGENT)
               - frustrated/urgent reporters on high-severity incidents need faster human contact.
            4. summary: one-sentence executive summary of the incident.
            5. rationale: brief justification citing evidence from the report.
            """;

    private final io.github.chdurai.agenticbanking.flow.FlowKit flow;
    private final LlmGateway llm;
    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;

    public IncidentTriageAgent(LlmGateway llm, io.github.chdurai.agenticbanking.flow.FlowKit flow,
                               io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities) {
        this.llm = llm;
        this.flow = flow;
        this.capabilities = capabilities;
    }

    private String system() {
        return flow.v2() ? SYSTEM + "\n" + io.github.chdurai.agenticbanking.guardrails.Spotlight.INSTRUCTION : SYSTEM;
    }

    private String fence(String text) {
        return flow.v2() ? flow.prompts().untrusted(text) : text;
    }

    public IncidentTriage triage(Incident incident) {
        try (var scope = capabilities.enter(NAME, incident.incidentId())) {
            return triageScoped(incident);
        }
    }

    private IncidentTriage triageScoped(Incident incident) {
        capabilities.checkView("incident-case");
        String user = """
                Incident %s: %s
                Service: %s | Environment: %s | Reporter: %s
                Description:
                %s
                """.formatted(incident.incidentId(), fence(incident.title()), incident.service(),
                incident.environment(), incident.reporter(), fence(incident.description()));

        IncidentTriage triage = llm.call(AgentCallSpec.of(NAME, system(), user, IncidentTriage.class, 0.0)).value();
        log.info("[{}] -> {} {} ({})", NAME, triage.severity(), triage.category(),
                triage.sentiment());
        return triage;
    }

}
