package io.github.chdurai.agenticbanking.grievance;

import io.github.chdurai.agenticbanking.llm.AgentCallSpec;
import io.github.chdurai.agenticbanking.llm.LlmGateway;
import io.github.chdurai.agenticbanking.guardrails.Spotlight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Grievance agent 1: thematic classification, severity, sentiment and risk flags. */
@Component
public class GrievanceTriageAgent {

    private static final Logger log = LoggerFactory.getLogger(GrievanceTriageAgent.class);
    static final String NAME = "grievance-triage";

    private static final String SYSTEM = """
            You are a grievance redress triage specialist for a development project's grievance
            mechanism. Classify the grievance:
            - themes: one or more of LABOR_AND_WORKING_CONDITIONS, COMMUNITY_HEALTH_AND_SAFETY,
              LAND_AND_RESETTLEMENT, ENVIRONMENTAL_POLLUTION, INDIGENOUS_PEOPLES_AND_CULTURAL_HERITAGE,
              STAKEHOLDER_ENGAGEMENT, CORRUPTION_OR_FRAUD, OTHER.
            - severity: LOW (inconvenience), MODERATE (real harm, no urgency), HIGH (serious or ongoing harm,
              or many people affected), CRITICAL (injury, loss of life, child or forced labor, imminent danger).
            - sentiment: NEUTRAL, CONCERNED, DISTRESSED or ANGRY.
            - retaliationRisk: true if the complainant mentions threats, intimidation or fear of reprisal.
            - vulnerableGroupsAffected: true if women, children, elderly, disabled or indigenous people are affected.
            - confidencePct: 0-100 confidence in your classification. Be honest; use below 60 when ambiguous.
            - summary: one neutral sentence. Do NOT include names or identifying details.
            - rationale: brief justification.
            The grievance may be in any language; reason in it but write summary and rationale in English.
            """ + Spotlight.INSTRUCTION;

    private final LlmGateway llm;
    private final io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities;

    public GrievanceTriageAgent(LlmGateway llm, io.github.chdurai.agenticbanking.capability.CapabilityGuard capabilities) {
        this.llm = llm;
        this.capabilities = capabilities;
    }

    public GrievanceTriage triage(Grievance g) {
        try (var scope = capabilities.enter(NAME, g.grievanceId())) {
            return triageScoped(g);
        }
    }

    private GrievanceTriage triageScoped(Grievance g) {
        capabilities.checkView("grievance-case");
        String user = "Project: %s | Channel: %s | Location: %s | Anonymous: %s\n\nGrievance:\n%s"
                .formatted(g.projectId(), g.channel(), g.location(), g.anonymous(),
                        Spotlight.wrap(g.description()));
        GrievanceTriage triage = llm.call(AgentCallSpec.of(NAME, SYSTEM, user, GrievanceTriage.class, 0.0)).value();
        log.info("[{}] -> {} {} confidence={}%", NAME, triage.severity(), triage.themes(),
                triage.confidencePct());
        return triage;
    }
}
